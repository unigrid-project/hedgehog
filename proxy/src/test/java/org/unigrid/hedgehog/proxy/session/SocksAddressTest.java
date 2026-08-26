/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.proxy.session;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.socks.UdpHeaderCodec;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class SocksAddressTest {
	private static final Socks5AddressType UNKNOWN_TYPE = Socks5AddressType.valueOf((byte) 9);
	private static final String IPV6_SYNTAX = ":[]%";
	private static final Map<Socks5AddressType, String> SAMPLE_HOSTS = Map.of(Socks5AddressType.IPv4, "192.0.2.1",
		Socks5AddressType.IPv6, "2001:db8::1", Socks5AddressType.DOMAIN, "example.org");

	/* The spellings a resolver, a URL parser or an operating system reads as an address: decimal, octal and hex
	   integers, short dotted forms, a trailing dot, and IPv6 with brackets, a zone or an embedded IPv4 address */
	private static final List<String> IP_ADDRESS_SPELLINGS = List.of("2130706433", "0177.0.0.1", "0x7f.1", "127.1",
		"127.0.0.1", "0x7f000001", "0x7f.0x0.0x0.0x1", "0X7F.1", "0X7F000001", "1.0X7F", "0xFF", "0xdeadBEEF",
		"017700000001", "4294967295", "4294967297", "1.2.3", "0", "00", "0x", "999.999.999.999", "127.0.0.1.",
		"127.0.0.1..", "example.123", "example.0x7f", "fe80::1%eth0", "fe80::1%1", "::1", "::", "2001:db8::1",
		"[::1]", "[127.0.0.1]", "[", "]", "::ffff:127.0.0.1", "::ffff:7f00:1", "[::ffff:127.0.0.1]",
		"64:ff9b::7f00:1", "1%eth0", "example.org:80", "a:b");

	/* Names that only look like they might be addresses, or that the destination policy has to judge */
	private static final List<String> NAMES = List.of("example.org", "Example.ORG", "localhost", "localhost.",
		"LOCALHOST", "foo.localhost", "example.org.", "127.0.0.1.nip.io", "1.2.3.4.example", "3com.com",
		"1e3.example", "a1", "1a", "0x7fg", "0xg", "x0x7f", "_dmarc.example.org", "my-host.example", "!", "~",
		"a".repeat(255));

	private static final List<String> UNUSABLE_NAMES = List.of("", " ", "a b", "exa mple.org", "exämple.org", "\u007f",
		"\u0000", "\u001f", "\u0080", String.valueOf((char) 0xFF), String.valueOf((char) 0x100),
		"\t", "ab\ncd", "a\r\nb", "a😀", "a" + (char) 0xA0 + "b", "ex\u0000ample.org");

	private static final List<String> NOT_IPV4 = List.of("", "1.2.3", "1.2.3.4.5", "2130706433", "0177.0.0.1",
		"0x7f.0.0.1", "1.2.3.256", "256.1.1.1", "1.2.3.4.", ".1.2.3.4", " 1.2.3.4", "1.2.3.4 ", "[1.2.3.4]",
		"1.2.3.4%eth0", "1.2.3.-4", "1,2,3,4", "example.org", "::1", "::ffff:1.2.3.4");

	private static final List<String> NOT_IPV6 = List.of("", "1.2.3.4", "::g", ":::", "1::2::3", "12345::",
		"1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "[::1", "::1]", "[[::1]]", "example.org", "2130706433", " ::1");

	private static SocksAddress ipv4(String host) {
		return new SocksAddress(Socks5AddressType.IPv4, host, 80);
	}

	private static SocksAddress ipv6(String host) {
		return new SocksAddress(Socks5AddressType.IPv6, host, 80);
	}

	private static SocksAddress domain(String host) {
		return new SocksAddress(Socks5AddressType.DOMAIN, host, 80);
	}

	private static SocksAddress sample(Socks5AddressType type, int port) {
		return new SocksAddress(type, SAMPLE_HOSTS.get(type), port);
	}

	private static boolean acceptsAsDomain(String host) {
		try {
			domain(host);
			return true;
		} catch (IllegalArgumentException refusal) {
			return false;
		}
	}

	@Provide
	Arbitrary<String> ipAddressSpellings() {
		return Arbitraries.of(IP_ADDRESS_SPELLINGS);
	}

	@Provide
	Arbitrary<String> names() {
		return Arbitraries.of(NAMES);
	}

	@Provide
	Arbitrary<String> unusableNames() {
		return Arbitraries.of(UNUSABLE_NAMES);
	}

	@Provide
	Arbitrary<String> notIpv4() {
		return Arbitraries.of(NOT_IPV4);
	}

	@Provide
	Arbitrary<String> notIpv6() {
		return Arbitraries.of(NOT_IPV6);
	}

	@Example
	public void shouldKeepIpLiteralsInTheirCanonicalForm() {
		assertThat(new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 80).host(), is("192.0.2.1"));
		assertThat(new SocksAddress(Socks5AddressType.IPv6, "2001:DB8:0:0:0:0:0:1", 443).host(), is("2001:db8::1"));
		assertThat(new SocksAddress(Socks5AddressType.IPv6, "[::1]", 22),
			equalTo(new SocksAddress(Socks5AddressType.IPv6, "0:0:0:0:0:0:0:1", 22)));
	}

	@Example
	public void shouldDropTheZoneOfAnIpv6Literal() {
		assertThat(new SocksAddress(Socks5AddressType.IPv6, "fe80::1%eth0", 22).host(), is("fe80::1"));
		assertThat(new SocksAddress(Socks5AddressType.IPv6, "2001:4860:4860::8888%1", 53).host(),
			is("2001:4860:4860::8888"));
	}

	@Property(tries = 100)
	public void shouldAcceptEveryIpv4Address(@ForAll @Size(4) byte[] bytes,
		@ForAll @IntRange(min = 0, max = 0xFFFF) int port) {
		final String host = (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + "."
			+ (bytes[3] & 0xff);

		assertThat(new SocksAddress(Socks5AddressType.IPv4, host, port).host(), is(host));
	}

	@Property(tries = 100)
	public void shouldHoldEveryIpv4AddressAsFourBytes(@ForAll @Size(4) byte[] bytes) {
		final SocksAddress address = ipv4(NetUtil.bytesToIpAddress(bytes));

		assertThat(NetUtil.createByteArrayFromIpAddressString(address.host()), is(bytes));
		assertThat(ipv4(address.host()), equalTo(address));
	}

	@Property(tries = 100)
	public void shouldHoldEveryIpv6AddressAsSixteenBytesInOneSpelling(@ForAll @Size(16) byte[] bytes) {
		final HexFormat hex = HexFormat.of().withUpperCase();
		final String spelled = IntStream.range(0, 8).mapToObj(i -> hex.formatHex(bytes, 2 * i, 2 * i + 2))
			.collect(Collectors.joining(":"));
		final SocksAddress address = ipv6(spelled);

		assertThat(NetUtil.createByteArrayFromIpAddressString(address.host()), is(bytes));
		assertThat(ipv6(address.host()), equalTo(address));
		assertThat(ipv6("[" + spelled + "]"), equalTo(address));
		assertThat(ipv6(spelled + "%eth0"), equalTo(address));
	}

	@Example
	public void shouldKeepAnIpv4MappedLiteralAsIpv6WithoutADottedQuad() {
		assertThat(ipv6("::ffff:127.0.0.1").host(), is("::ffff:7f00:1"));
		assertThat(ipv6("::FFFF:7F00:1"), equalTo(ipv6("0:0:0:0:0:ffff:127.0.0.1")));
		assertThat(ipv6("::7f00:1").host(), is("::7f00:1"));
		Expect.assertThrows(IllegalArgumentException.class, () -> ipv4("::ffff:127.0.0.1"));
	}

	@Example
	public void shouldKeepADomainNameAsGiven() {
		final SocksAddress address = new SocksAddress(Socks5AddressType.DOMAIN, "Example.org", 443);

		assertThat(address.host(), is("Example.org"));
		assertThat(new SocksAddress(Socks5AddressType.DOMAIN, "a".repeat(255), 1).host().length(), is(255));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldCarryNamesUnchanged(@ForAll("names") String name) {
		assertThat(domain(name).host(), is(name));
		assertThat(domain(name).type(), is(Socks5AddressType.DOMAIN));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseEverySpellingOfAnIpAddressAsADomain(@ForAll("ipAddressSpellings") String spelling) {
		Expect.assertThrows(IllegalArgumentException.class, () -> domain(spelling));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseANameThatIsNotPrintableAscii(@ForAll("unusableNames") String name) {
		Expect.assertThrows(IllegalArgumentException.class, () -> domain(name));
	}

	@Example
	public void shouldAcceptExactlyThePrintableAsciiCharactersWithoutIpv6SyntaxInADomain() {
		for (int character = 0; character <= Character.MAX_VALUE; character++) {
			final boolean expected = character >= '!' && character <= '~' && IPV6_SYNTAX.indexOf(character) < 0;
			final String host = "a" + (char) character + "a";

			assertThat("U+" + Integer.toHexString(character), acceptsAsDomain(host), is(expected));
		}
	}

	@Example
	public void shouldRefuseALiteralOfAnotherFamily() {
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.IPv4, "::1", 1));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.IPv6, "127.0.0.1", 1));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.IPv4, "example.org", 1));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.valueOf((byte) 9), "127.0.0.1", 1));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseAnIpv4LiteralThatIsNotFourDecimalOctets(@ForAll("notIpv4") String host) {
		Expect.assertThrows(IllegalArgumentException.class, () -> ipv4(host));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void shouldRefuseAnIpv6LiteralThatIsNotOne(@ForAll("notIpv6") String host) {
		Expect.assertThrows(IllegalArgumentException.class, () -> ipv6(host));
	}

	@Example
	public void shouldRefuseAnUnknownAddressTypeWhateverTheHost() {
		for (String host : List.of("127.0.0.1", "::1", "example.org", "")) {
			Expect.assertThrows(IllegalArgumentException.class, () -> new SocksAddress(UNKNOWN_TYPE, host, 1));
		}
	}

	@Example
	public void shouldRefuseADomainNameThatSocksCannotCarry() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new SocksAddress(Socks5AddressType.DOMAIN, "", 1));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.DOMAIN, "a".repeat(256), 1));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.DOMAIN, "exa mple.org", 1));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.DOMAIN, "exämple.org", 1));
	}

	@Example
	public void shouldAcceptADomainNameUpToTheLimit() {
		for (int length : List.of(1, 2, 254, 255)) {
			assertThat(domain("a".repeat(length)).host().length(), is(length));
		}
	}

	@Example
	public void shouldRefuseAnEmptyOrTooLongDomainName() {
		for (int length : List.of(0, 256, 257, 1000, 100_000)) {
			Expect.assertThrows(IllegalArgumentException.class, () -> domain("a".repeat(length)));
		}

		assertThat(SocksAddress.MAX_DOMAIN_LENGTH, is(255));
	}

	@Example
	public void shouldRefuseAPortBeyondSixteenBits() {
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 0x10000));
		Expect.assertThrows(IllegalArgumentException.class,
			() -> new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", -1));
	}

	@Example
	public void shouldRefuseAPortOutsideTheSixteenBitRangeInEveryFamily() {
		for (Socks5AddressType type : SAMPLE_HOSTS.keySet()) {
			for (int port : List.of(-1, 0x10000, 0x10050, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
				Expect.assertThrows(IllegalArgumentException.class, () -> sample(type, port));
			}
		}
	}

	@Example
	public void shouldAcceptTheFirstAndTheLastPortInEveryFamily() {
		for (Socks5AddressType type : SAMPLE_HOSTS.keySet()) {
			assertThat(sample(type, 0).port(), is(0));
			assertThat(sample(type, 1).port(), is(1));
			assertThat(sample(type, 0xFFFF).port(), is(0xFFFF));
			assertThat(sample(type, 0xFFFE).port(), is(0xFFFE));
		}

		assertThat(SocksAddress.MAX_PORT, is(0xFFFF));
	}

	@Property(tries = 100)
	public void shouldKeepAnyPortOfSixteenBits(@ForAll @IntRange(min = 0, max = 0xFFFF) int port) {
		assertThat(sample(Socks5AddressType.IPv4, port).port(), is(port));
	}

	@Example
	public void shouldRefuseNullArguments() {
		assertThat(Expect.assertThrows(NullPointerException.class,
			() -> new SocksAddress(null, "192.0.2.1", 1)).getMessage(), is("type"));

		for (Socks5AddressType type : SAMPLE_HOSTS.keySet()) {
			assertThat(Expect.assertThrows(NullPointerException.class,
				() -> new SocksAddress(type, null, 1)).getMessage(), is("host"));
		}

		Expect.assertThrows(IllegalArgumentException.class, () -> SocksAddress.sizeOf(null));
	}

	@Example
	public void shouldNotRepeatAnUntrustedHostInARefusal() {
		final String hostile = "evil\r\n\u001b[31m";

		for (Expect.Action refused : List.<Expect.Action>of(() -> domain(hostile), () -> domain("evil:80"),
			() -> ipv4(hostile), () -> ipv6(hostile))) {
			assertThat(Expect.assertThrows(IllegalArgumentException.class, refused).getMessage(),
				not(containsString("evil")));
		}
	}

	@Example
	public void shouldBeEqualOnlyWhenTypeHostAndPortAreEqual() {
		final SocksAddress address = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 80);

		assertThat(address, equalTo(new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 80)));
		assertThat(address.hashCode(), is(new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 80).hashCode()));
		assertThat(address, not(equalTo(new SocksAddress(Socks5AddressType.IPv4, "192.0.2.2", 80))));
		assertThat(address, not(equalTo(new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 81))));
		assertThat(new SocksAddress(Socks5AddressType.IPv4, "0.0.0.0", 0),
			not(equalTo(new SocksAddress(Socks5AddressType.IPv6, "::", 0))));
		assertThat(domain("example.org"), not(equalTo(domain("example.com"))));
	}

	@Example
	public void shouldBeEqualWhateverTheSpellingOfAnIpv6Literal() {
		final SocksAddress canonical = ipv6("2001:db8::1");

		assertThat(ipv6("[2001:DB8:0:0:0:0:0:1]"), equalTo(canonical));
		assertThat(ipv6("2001:DB8:0:0:0:0:0:1%eth0"), equalTo(canonical));
		assertThat(ipv6("2001:DB8:0:0:0:0:0:1").hashCode(), is(canonical.hashCode()));
	}

	@Example
	public void shouldBeImmutable() {
		final List<Field> fields = Arrays.stream(SocksAddress.class.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers())).toList();

		assertThat(fields.stream().map(Field::getName).toList(), containsInAnyOrder("type", "host", "port"));
		assertThat(fields.stream().allMatch(field -> Modifier.isFinal(field.getModifiers())), is(true));
	}

	@Example
	public void shouldOfferAnUnspecifiedAddressForReplies() {
		assertThat(SocksAddress.UNSPECIFIED, equalTo(new SocksAddress(Socks5AddressType.IPv4, "0.0.0.0", 0)));
		assertThat(SocksAddress.UNSPECIFIED.type(), is(Socks5AddressType.IPv4));
		assertThat(SocksAddress.UNSPECIFIED.host(), is("0.0.0.0"));
		assertThat(SocksAddress.UNSPECIFIED.port(), is(0));
		assertThat(SocksAddress.sizeOf(Socks5AddressType.IPv6), is(16));
	}

	@Example
	public void shouldKnowTheSizeOfAnIpAddress() {
		assertThat(SocksAddress.sizeOf(Socks5AddressType.IPv4), is(4));
		assertThat(SocksAddress.sizeOf(Socks5AddressType.IPv6), is(16));
		Expect.assertThrows(IllegalArgumentException.class, () -> SocksAddress.sizeOf(Socks5AddressType.DOMAIN));
		Expect.assertThrows(IllegalArgumentException.class, () -> SocksAddress.sizeOf(UNKNOWN_TYPE));
	}

	@Example
	public void shouldCountTheBytesOfItsSocksForm() {
		assertThat(ipv4("192.0.2.1").encodedSize(), is(7));
		assertThat(ipv4("255.255.255.255").encodedSize(), is(7));
		assertThat(ipv6("::1").encodedSize(), is(19));
		assertThat(ipv6("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff").encodedSize(), is(19));
		assertThat(domain("example.org").encodedSize(), is(15));
		assertThat(domain("a").encodedSize(), is(5));
		assertThat(domain("a".repeat(SocksAddress.MAX_DOMAIN_LENGTH - 1)).encodedSize(), is(258));
	}

	/* The longest address is all a SOCKS5 UDP request header holds after its reserved and fragment bytes */
	@Example
	public void shouldCountTheLongestAddressAsTheRestOfTheLongestUdpHeader() {
		final SocksAddress longest = new SocksAddress(Socks5AddressType.DOMAIN,
			"a".repeat(SocksAddress.MAX_DOMAIN_LENGTH), SocksAddress.MAX_PORT);

		assertThat(longest.encodedSize(), is(259));
		assertThat(longest.encodedSize(), is(UdpHeaderCodec.MAX_HEADER_BYTES - UdpHeaderCodec.PREFIX_BYTES));
	}

	@Provide
	Arbitrary<SocksAddress> addresses() {
		final Arbitrary<Integer> ports = Arbitraries.integers().between(0, SocksAddress.MAX_PORT);
		final Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1)
			.ofMaxLength(SocksAddress.MAX_DOMAIN_LENGTH);

		return Arbitraries.oneOf(
			Combinators.combine(Arbitraries.bytes().array(byte[].class).ofSize(4), ports).as((bytes, port) ->
				new SocksAddress(Socks5AddressType.IPv4, NetUtil.bytesToIpAddress(bytes), port)),
			Combinators.combine(Arbitraries.bytes().array(byte[].class).ofSize(16), ports).as((bytes, port) ->
				new SocksAddress(Socks5AddressType.IPv6, NetUtil.bytesToIpAddress(bytes), port)),
			Combinators.combine(names, ports).as((name, port) ->
				new SocksAddress(Socks5AddressType.DOMAIN, name, port)));
	}

	/* A sealed Datagram puts its type byte in front of the address, a UDP request header its reserved and fragment
	   bytes */
	@Property(tries = 300)
	public void shouldCountWhatTheCodecsWrite(@ForAll("addresses") SocksAddress address) {
		final Datagram empty = new Datagram(address, new byte[0]);

		assertThat(address.encodedSize(), is(SealedCodec.encode(empty).length - 1));
		assertThat(address.encodedSize(), is(UdpHeaderCodec.encode(empty).length - UdpHeaderCodec.PREFIX_BYTES));
	}
}
