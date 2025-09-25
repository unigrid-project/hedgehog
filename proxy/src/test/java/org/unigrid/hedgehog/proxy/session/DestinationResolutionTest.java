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
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class DestinationResolutionTest {
	private static final Resolver NEVER = host -> {
		throw new AssertionError("An IP literal is never resolved, asked for " + host);
	};
	private static final List<String> WRAPPED_LOCAL_LITERALS = List.of("::ffff:a9fe:a9fe", "::a9fe:a9fe",
		"64:ff9b::a9fe:a9fe", "::ffff:169.254.169.254", "::ffff:10.0.0.1");

	private static InetAddress address(String literal) throws UnknownHostException {
		return InetAddress.getByAddress(NetUtil.createByteArrayFromIpAddressString(literal));
	}

	private static Resolver answering(Map<String, List<String>> answers) {
		return host -> {
			if (!answers.containsKey(host)) {
				throw new UnknownHostException(host);
			}

			final List<InetAddress> addresses = new ArrayList<>();

			for (String literal : answers.get(host)) {
				addresses.add(address(literal));
			}

			return addresses;
		};
	}

	private static Resolver counting(Resolver resolver, List<String> asked) {
		return host -> {
			asked.add(host);
			return resolver.resolveAll(host);
		};
	}

	private static SocksAddress domain(String name) {
		return new SocksAddress(Socks5AddressType.DOMAIN, name, 443);
	}

	private static DestinationRefusedException refused(DestinationPolicy policy, SocksAddress destination,
		Resolver resolver) {
		return Expect.assertThrows(DestinationRefusedException.class, () -> policy.admit(destination, resolver));
	}

	private static Socks5CommandStatus refusal(DestinationPolicy policy, SocksAddress destination, Resolver resolver) {
		return refused(policy, destination, resolver).status();
	}

	private static SocksAddress ipv4Literal(String literal) {
		return new SocksAddress(Socks5AddressType.IPv4, literal, 80);
	}

	private static SocksAddress ipv6Literal(String literal) {
		return new SocksAddress(Socks5AddressType.IPv6, literal, 80);
	}

	private static InetAddress ipv4(int first, int host) throws UnknownHostException {
		final byte[] bytes = { (byte) first, (byte) (host >> 16), (byte) (host >> 8), (byte) host };

		return InetAddress.getByAddress(bytes);
	}

	@Example
	public void shouldAdmitAPublicLiteralWithoutResolvingIt() throws Exception {
		assertThat(DestinationPolicy.publicOnly().admit(new SocksAddress(Socks5AddressType.IPv4, "8.8.8.8", 53),
			NEVER), equalTo(List.of(address("8.8.8.8"))));
		assertThat(DestinationPolicy.publicOnly().admit(new SocksAddress(Socks5AddressType.IPv6,
			"2001:4860:4860::8888", 53), NEVER), equalTo(List.of(address("2001:4860:4860::8888"))));
	}

	@Example
	public void shouldDropTheZoneOfALiteralAndJudgeTheAddressBehindIt() throws Exception {
		assertThat(refusal(DestinationPolicy.publicOnly(), new SocksAddress(Socks5AddressType.IPv6, "fe80::1%eth0",
			22), NEVER), is(Socks5CommandStatus.FORBIDDEN));
		assertThat(DestinationPolicy.publicOnly().admit(new SocksAddress(Socks5AddressType.IPv6,
			"2001:4860:4860::8888%eth0", 53), NEVER), equalTo(List.of(address("2001:4860:4860::8888"))));
	}

	@Example
	public void shouldRefuseALocalLiteral() {
		assertThat(refusal(DestinationPolicy.publicOnly(), new SocksAddress(Socks5AddressType.IPv4, "10.0.0.1", 22),
			NEVER), is(Socks5CommandStatus.FORBIDDEN));
		assertThat(refusal(DestinationPolicy.publicOnly(), new SocksAddress(Socks5AddressType.IPv6,
			"::ffff:192.168.0.1", 22), NEVER), is(Socks5CommandStatus.FORBIDDEN));
	}

	@Example
	public void shouldRefuseALiteralThatWrapsALocalAddressWithoutResolvingIt() {
		for (String literal : WRAPPED_LOCAL_LITERALS) {
			assertThat(literal, refusal(DestinationPolicy.publicOnly(), ipv6Literal(literal), NEVER),
				is(Socks5CommandStatus.FORBIDDEN));
		}

		assertThat(refusal(DestinationPolicy.publicOnly(), ipv4Literal("169.254.169.254"), NEVER),
			is(Socks5CommandStatus.FORBIDDEN));
	}

	@Example
	public void shouldJudgeALiteralByTheLoopbackSettingOfThePolicy() throws Exception {
		assertThat(DestinationPolicy.allowLoopback().admit(ipv4Literal("127.0.0.1"), NEVER),
			equalTo(List.of(address("127.0.0.1"))));
		assertThat(refusal(DestinationPolicy.allowLoopback(), ipv4Literal("10.0.0.1"), NEVER),
			is(Socks5CommandStatus.FORBIDDEN));
		assertThat(refusal(DestinationPolicy.publicOnly(), ipv4Literal("127.0.0.1"), NEVER),
			is(Socks5CommandStatus.FORBIDDEN));
	}

	@Example
	public void shouldAdmitEveryAddressOfAPublicName() throws Exception {
		final Resolver resolver = answering(Map.of("example.org", List.of("93.184.215.14", "2606:2800:21f::1")));

		assertThat(DestinationPolicy.publicOnly().admit(domain("example.org"), resolver),
			equalTo(List.of(address("93.184.215.14"), address("2606:2800:21f::1"))));
	}

	@Example
	public void shouldReturnTheCheckedAddressesInTheOrderOfTheAnswer() throws Exception {
		final Resolver resolver = answering(Map.of("example.org", List.of("8.8.8.8", "2606:4700:4700::1111",
			"1.1.1.1")));

		assertThat(DestinationPolicy.publicOnly().admit(domain("example.org"), resolver),
			equalTo(List.of(address("8.8.8.8"), address("2606:4700:4700::1111"), address("1.1.1.1"))));
	}

	@Example
	public void shouldRefuseANameWithAnyLocalAddress() {
		final Resolver resolver = answering(Map.of("mixed.example", List.of("93.184.215.14", "192.168.1.10"),
			"localhost", List.of("127.0.0.1", "::1")));

		assertThat(refusal(DestinationPolicy.publicOnly(), domain("mixed.example"), resolver),
			is(Socks5CommandStatus.FORBIDDEN));
		assertThat(refusal(DestinationPolicy.publicOnly(), domain("localhost"), resolver),
			is(Socks5CommandStatus.FORBIDDEN));
		assertThat(refusal(DestinationPolicy.allowLoopback(), domain("mixed.example"), resolver),
			is(Socks5CommandStatus.FORBIDDEN));
	}

	@Example
	public void shouldRefuseTheWholeAnswerWhereverTheLocalAddressStands() {
		final List<List<String>> answers = List.of(List.of("93.184.215.14", "127.0.0.1"),
			List.of("127.0.0.1", "93.184.215.14"), List.of("93.184.215.14", "10.0.0.1", "8.8.8.8"),
			List.of("8.8.8.8", "93.184.215.14", "::1"), List.of("fe80::1", "8.8.8.8", "1.1.1.1"),
			List.of("8.8.8.8", "::ffff:169.254.169.254"),
			List.of("2606:2800:21f::1", "8.8.8.8", "64:ff9b::a00:1"));

		for (List<String> answer : answers) {
			assertThat(answer.toString(), refusal(DestinationPolicy.publicOnly(), domain("mixed.example"),
				answering(Map.of("mixed.example", answer))), is(Socks5CommandStatus.FORBIDDEN));
		}
	}

	@Example
	public void shouldJudgeLocalhostStyleNamesByTheAddressesTheyResolveTo() {
		final List<List<String>> localAnswers = List.of(List.of("127.0.0.1", "::1"), List.of("::1"),
			List.of("127.0.0.2"), List.of("::ffff:127.0.0.1"), List.of("0.0.0.0"));

		for (String name : List.of("localhost", "localhost.", "LOCALHOST", "app.localhost", "app.localhost.")) {
			for (List<String> answer : localAnswers) {
				assertThat(name + answer, refusal(DestinationPolicy.publicOnly(), domain(name),
					answering(Map.of(name, answer))), is(Socks5CommandStatus.FORBIDDEN));
			}
		}
	}

	@Example
	public void shouldAdmitLoopbackNamesWhenTestsAskForIt() throws Exception {
		final Resolver resolver = answering(Map.of("localhost", List.of("127.0.0.1", "::1")));

		assertThat(DestinationPolicy.allowLoopback().admit(domain("localhost"), resolver),
			equalTo(List.of(address("127.0.0.1"), address("::1"))));
	}

	@Example
	public void shouldCallANameWithoutAddressesUnreachable() {
		final Resolver resolver = answering(Map.of("empty.example", List.of()));

		assertThat(refusal(DestinationPolicy.publicOnly(), domain("empty.example"), resolver),
			is(Socks5CommandStatus.HOST_UNREACHABLE));
		assertThat(refusal(DestinationPolicy.publicOnly(), domain("unknown.example"), resolver),
			is(Socks5CommandStatus.HOST_UNREACHABLE));
	}

	@Example
	public void shouldTakeAResolverFailureAsAnUnreachableNameWithoutItsDetail() {
		final Resolver failing = host -> {
			throw new UnknownHostException("resolver detail about " + host);
		};
		final DestinationRefusedException refusal = refused(DestinationPolicy.publicOnly(), domain("down.example"),
			failing);

		assertThat(refusal.status(), is(Socks5CommandStatus.HOST_UNREACHABLE));
		assertThat(refusal.getMessage(), is("down.example does not resolve"));
		assertThat(refusal.getCause(), is(nullValue()));
	}

	@Example
	public void shouldNameOnlyTheDestinationAndNeverItsAddressesInARefusal() {
		final Resolver resolver = answering(Map.of("mixed.example", List.of("93.184.215.14", "192.168.1.10"),
			"empty.example", List.of()));

		assertThat(refused(DestinationPolicy.publicOnly(), domain("mixed.example"), resolver).getMessage(),
			is("mixed.example is not a public destination"));
		assertThat(refused(DestinationPolicy.publicOnly(), domain("empty.example"), resolver).getMessage(),
			is("empty.example has no address"));
		assertThat(refused(DestinationPolicy.publicOnly(), ipv4Literal("10.0.0.1"), NEVER).getMessage(),
			is("10.0.0.1 is not a public destination"));
	}

	@Example
	public void shouldAskTheResolverOncePerNameThatIsRefused() {
		final Resolver resolver = answering(Map.of("mixed.example", List.of("93.184.215.14", "192.168.1.10"),
			"empty.example", List.of()));

		for (String name : List.of("mixed.example", "empty.example", "unknown.example")) {
			final List<String> asked = new ArrayList<>();

			refused(DestinationPolicy.publicOnly(), domain(name), counting(resolver, asked));
			assertThat(name, asked, equalTo(List.of(name)));
		}
	}

	@Example
	public void shouldConnectOnlyToTheFirstAnswerWhenALaterLookupWouldRebindTheName() throws Exception {
		final List<String> asked = new ArrayList<>();
		final Resolver first = answering(Map.of("example.org", List.of("93.184.215.14")));
		final Resolver later = answering(Map.of("example.org", List.of("127.0.0.1")));
		final Resolver rebinding = host -> {
			asked.add(host);
			return (asked.size() == 1 ? first : later).resolveAll(host);
		};

		assertThat(DestinationPolicy.publicOnly().admit(domain("example.org"), rebinding),
			equalTo(List.of(address("93.184.215.14"))));
		assertThat(asked, equalTo(List.of("example.org")));
	}

	@Example
	public void shouldKeepTheCheckedAddressesWhenTheResolverChangesItsListAfterwards() throws Exception {
		final List<InetAddress> answer = new ArrayList<>(List.of(address("8.8.8.8")));

		final List<InetAddress> admitted = DestinationPolicy.publicOnly().admit(domain("example.org"),
			host -> answer);

		answer.add(address("127.0.0.1"));
		answer.set(0, address("10.0.0.1"));
		assertThat(admitted, equalTo(List.of(address("8.8.8.8"))));
		Expect.assertThrows(UnsupportedOperationException.class, () -> admitted.add(address("127.0.0.1")));
	}

	@Property(tries = 200)
	public void shouldAdmitPublicAnswersInOrderAndRefuseOneLocalAddressAtAnyPosition(
		@ForAll @Size(min = 1, max = 6) List<@IntRange(min = 0, max = 0xFFFFFF) Integer> hosts,
		@ForAll @IntRange(min = 0, max = 0xFFFFFF) int local, @ForAll @IntRange(min = 0, max = 6) int position)
		throws Exception {
		final List<InetAddress> publics = new ArrayList<>();

		for (int host : hosts) {
			publics.add(ipv4(11, host));
		}

		final List<InetAddress> mixed = new ArrayList<>(publics);
		final List<String> askedPublic = new ArrayList<>();
		final List<String> askedMixed = new ArrayList<>();

		mixed.add(position % (publics.size() + 1), ipv4(10, local));
		assertThat(DestinationPolicy.publicOnly().admit(domain("any.example"),
			counting(host -> publics, askedPublic)), equalTo(publics));
		assertThat(refusal(DestinationPolicy.publicOnly(), domain("any.example"),
			counting(host -> mixed, askedMixed)), is(Socks5CommandStatus.FORBIDDEN));
		assertThat(askedPublic, equalTo(List.of("any.example")));
		assertThat(askedMixed, equalTo(List.of("any.example")));
	}

	@Example
	public void shouldRefuseAnIpSpelledNameWhereTheAddressIsBuilt() {
		for (String spelling : List.of("127.0.0.1", "2130706433", "0177.0.0.1", "0x7f.1", "127.1", "127.0.0.1.",
			"::1", "[::1]", "fe80::1%eth0", "::ffff:127.0.0.1")) {
			Expect.assertThrows(IllegalArgumentException.class, () -> domain(spelling));
		}
	}

	@Example
	public void shouldTakeALiteralAddressFromTheSystemResolverWithoutALookup() throws Exception {
		assertThat(refusal(DestinationPolicy.publicOnly(), ipv4Literal("127.0.0.1"), Resolver.system()),
			is(Socks5CommandStatus.FORBIDDEN));
		assertThat(refusal(DestinationPolicy.publicOnly(), ipv6Literal("::1"), Resolver.system()),
			is(Socks5CommandStatus.FORBIDDEN));
		assertThat(DestinationPolicy.publicOnly().admit(ipv4Literal("8.8.8.8"), Resolver.system()),
			equalTo(List.of(address("8.8.8.8"))));
	}

	@Example
	public void shouldParseAnIpLiteralInTheSystemResolverWithoutALookup() throws Exception {
		assertThat(Resolver.system().resolveAll("127.0.0.1"), equalTo(List.of(address("127.0.0.1"))));
		assertThat(Resolver.system().resolveAll("2001:4860:4860::8888"),
			equalTo(List.of(address("2001:4860:4860::8888"))));
	}
}
