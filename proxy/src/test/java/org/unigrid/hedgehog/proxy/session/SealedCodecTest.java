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
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.NetUtil;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

public class SealedCodecTest {
	private static final int MAX_BYTES = 16 * 1024;

	private static final SocksAddress DOMAIN_ADDRESS = new SocksAddress(Socks5AddressType.DOMAIN, "a.io", 443);
	private static final SocksAddress IPV4_ADDRESS = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 8080);
	private static final SocksAddress IPV6_ADDRESS = new SocksAddress(Socks5AddressType.IPv6, "::1", 53);
	private static final List<SocksAddress> ADDRESSES = List.of(DOMAIN_ADDRESS, IPV4_ADDRESS, IPV6_ADDRESS);

	private static final byte[] DOMAIN_WIRE = wire(3, 4, 'a', '.', 'i', 'o', 1, 0xbb);
	private static final byte[] IPV4_WIRE = wire(1, 192, 0, 2, 1, 0x1f, 0x90);
	private static final byte[] IPV6_WIRE = wire(4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53);
	private static final byte[] PAYLOAD = wire(7, 8, 9);

	private static final List<SealedMessage> FIXED_LENGTH_MESSAGES = List.of(
		new Connect(Socks5CommandType.CONNECT, DOMAIN_ADDRESS), new Connect(Socks5CommandType.CONNECT, IPV4_ADDRESS),
		new Connect(Socks5CommandType.UDP_ASSOCIATE, IPV6_ADDRESS),
		new Connected(Socks5CommandStatus.SUCCESS, DOMAIN_ADDRESS),
		new Connected(Socks5CommandStatus.FAILURE, IPV4_ADDRESS),
		new Connected(Socks5CommandStatus.ADDRESS_UNSUPPORTED, IPV6_ADDRESS), new Close(CloseReason.DONE),
		new Close(CloseReason.BROKEN));

	private static byte[] wire(int... values) {
		final byte[] bytes = new byte[values.length];

		for (int index = 0; index < values.length; index++) {
			bytes[index] = (byte) values[index];
		}

		return bytes;
	}

	private static byte[] join(byte[]... parts) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (byte[] part : parts) {
			out.writeBytes(part);
		}

		return out.toByteArray();
	}

	private static byte[] connectTo(String name) {
		return join(wire(1, 1, 3, name.length()), name.getBytes(StandardCharsets.US_ASCII), wire(0, 80));
	}

	private static String exceeding(int size, int limit) {
		return "A message of " + size + " bytes exceeds the limit of " + limit + " bytes";
	}

	private static List<SealedMessage> everyKindOfMessage() {
		final List<SealedMessage> messages = new ArrayList<>(List.of(new Data(new byte[0]), new Data(PAYLOAD)));

		for (SocksAddress address : ADDRESSES) {
			messages.add(new Connect(Socks5CommandType.CONNECT, address));
			messages.add(new Connect(Socks5CommandType.UDP_ASSOCIATE, address));
			messages.add(new Datagram(address, new byte[0]));
			messages.add(new Datagram(address, PAYLOAD));

			for (int reply = 0; reply <= 8; reply++) {
				messages.add(new Connected(Socks5CommandStatus.valueOf((byte) reply), address));
			}
		}

		for (CloseReason reason : CloseReason.values()) {
			messages.add(new Close(reason));
		}

		return messages;
	}

	private static Arbitrary<SocksAddress> addresses() {
		final Arbitrary<Integer> ports = Arbitraries.integers().between(0, 0xFFFF);
		final Arbitrary<String> ipv4 = Arbitraries.bytes().array(byte[].class).ofSize(4).map(NetUtil::bytesToIpAddress);
		final Arbitrary<String> ipv6 = Arbitraries.bytes().array(byte[].class).ofSize(16)
			.map(NetUtil::bytesToIpAddress);

		/* A name must carry no IP syntax and end in a label that is not a number, which ".test" guarantees */
		final Arbitrary<String> domains = Arbitraries.strings().withCharRange('!', '~').ofMaxLength(250)
			.map(name -> name.replaceAll("[\\[\\]:%]", "-") + ".test");

		return Arbitraries.oneOf(Combinators.combine(ipv4, ports).as((host, port) -> new SocksAddress(
			Socks5AddressType.IPv4, host, port)), Combinators.combine(ipv6, ports).as((host, port) -> new SocksAddress(
			Socks5AddressType.IPv6, host, port)), Combinators.combine(domains, ports).as((host, port) -> new SocksAddress(
			Socks5AddressType.DOMAIN, host, port)));
	}

	private static Arbitrary<byte[]> payloads() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(300);
	}

	@Provide
	public Arbitrary<SealedMessage> fixedMessages() {
		return Arbitraries.oneOf(Combinators.combine(Arbitraries.of(Socks5CommandType.CONNECT,
			Socks5CommandType.UDP_ASSOCIATE), addresses()).as(Connect::new), Combinators.combine(Arbitraries.integers()
			.between(0, 8).map(code -> Socks5CommandStatus.valueOf(code.byteValue())), addresses()).as(Connected::new),
			Arbitraries.of(CloseReason.class).map(Close::new));
	}

	@Provide
	public Arbitrary<SealedMessage> messages() {
		return Arbitraries.oneOf(fixedMessages(), payloads().map(Data::new),
			Combinators.combine(addresses(), payloads()).as(Datagram::new));
	}

	@Provide
	public Arbitrary<byte[]> tamperedEncodings() {
		return Combinators.combine(messages().map(SealedCodec::encode), Arbitraries.integers(), Arbitraries.integers(),
			Arbitraries.bytes()).as((encoded, length, position, value) -> {
				final byte[] bytes = Arrays.copyOf(encoded, Math.floorMod(length, encoded.length + 5));

				if (bytes.length > 0) {
					bytes[Math.floorMod(position, bytes.length)] = value;
				}

				return bytes;
			});
	}

	private static String refusal(byte[] bytes) {
		return refusal(bytes, MAX_BYTES);
	}

	private static String refusal(byte[] bytes, int maxBytes) {
		return Expect.assertThrows(MalformedMessageException.class, () -> SealedCodec.decode(bytes, maxBytes))
			.getMessage();
	}

	private static void refuseEveryByteExcept(List<Integer> known, String prefix, IntFunction<byte[]> input) {
		for (int value = 0; value < 256; value++) {
			if (!known.contains(value)) {
				assertThat("byte " + value, refusal(input.apply(value)), startsWith(prefix));
			}
		}
	}

	private static void assertDecodesCanonicallyOrIsRefused(byte[] bytes, int maxBytes) {
		try {
			final SealedMessage message = SealedCodec.decode(bytes, maxBytes);

			assertThat(bytes.length <= maxBytes, is(true));
			assertThat(SealedCodec.encode(message), equalTo(bytes));
		} catch (MalformedMessageException refusal) {
			assertThat(refusal.getMessage().isEmpty(), is(false));
			assertThat(refusal.getMessage().startsWith("A message of"), is(bytes.length > maxBytes));
		}
	}

	@Property(tries = 500)
	public void shouldDecodeWhatItEncoded(@ForAll("messages") SealedMessage message) throws MalformedMessageException {
		assertThat(SealedCodec.decode(SealedCodec.encode(message), MAX_BYTES), equalTo(message));
	}

	@Example
	public void shouldLayOutMessagesAsDocumented() {
		final SocksAddress domain = new SocksAddress(Socks5AddressType.DOMAIN, "a.io", 443);
		final SocksAddress ipv4 = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", 8080);
		final SocksAddress ipv6 = new SocksAddress(Socks5AddressType.IPv6, "::1", 53);

		assertThat(SealedCodec.encode(new Connect(Socks5CommandType.CONNECT, domain)),
			equalTo(new byte[] { 1, 1, 3, 4, 'a', '.', 'i', 'o', 1, (byte) 0xbb }));
		assertThat(SealedCodec.encode(new Connected(Socks5CommandStatus.SUCCESS, ipv4)),
			equalTo(new byte[] { 2, 0, 1, (byte) 192, 0, 2, 1, 0x1f, (byte) 0x90 }));
		assertThat(SealedCodec.encode(new Datagram(ipv6, new byte[] { 7 })),
			equalTo(new byte[] { 4, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 53, 7 }));
		assertThat(SealedCodec.encode(new Data(new byte[] { 9, 8 })), equalTo(new byte[] { 3, 9, 8 }));
		assertThat(SealedCodec.encode(new Close(CloseReason.IDLE)), equalTo(new byte[] { 5, 1 }));
	}

	@Property(tries = 300)
	public void shouldRefuseEveryTruncation(@ForAll("fixedMessages") SealedMessage message, @ForAll int cut) {
		final byte[] encoded = SealedCodec.encode(message);

		assertThat(refusal(Arrays.copyOf(encoded, Math.floorMod(cut, encoded.length))), startsWith("Truncated"));
	}

	@Property(tries = 100)
	public void shouldRefuseBytesAfterAMessage(@ForAll("fixedMessages") SealedMessage message,
		@ForAll @Size(min = 1, max = 8) byte[] extra) {
		final byte[] encoded = SealedCodec.encode(message);
		final byte[] longer = Arrays.copyOf(encoded, encoded.length + extra.length);

		System.arraycopy(extra, 0, longer, encoded.length, extra.length);
		assertThat(refusal(longer), startsWith("Found " + extra.length + " bytes after"));
	}

	@Example
	public void shouldRefuseUnknownCodes() {
		assertThat(refusal(new byte[] { 6 }), startsWith("Unknown message type"));
		assertThat(refusal(new byte[] { 5, 9 }), startsWith("Unknown close reason"));
		assertThat(refusal(new byte[] { 1, 2, 1, 1, 2, 3, 4, 0, 80 }), startsWith("A session carries"));
		assertThat(refusal(new byte[] { 2, 9, 1, 1, 2, 3, 4, 0, 80 }), startsWith("SOCKS5 has no reply"));
		assertThat(refusal(new byte[] { 1, 1, 2, 1, 2, 3, 4, 0, 80 }), startsWith("Address type"));
	}

	@Example
	public void shouldRefuseADomainSocksCannotCarry() {
		assertThat(refusal(new byte[] { 1, 1, 3, 0, 0, 80 }), startsWith("A domain name"));
		assertThat(refusal(new byte[] { 1, 1, 3, 1, (byte) 0xe4, 0, 80 }), startsWith("A domain name"));
		assertThat(refusal(new byte[] { 1, 1, 3, 1, ' ', 0, 80 }), startsWith("A domain name"));
	}

	@Example
	public void shouldTakeAMessageAtTheLimitAndRefuseOneOverIt() throws MalformedMessageException {
		final Data atTheLimit = new Data(new byte[MAX_BYTES - SealedCodec.DATA_OVERHEAD]);
		final byte[] overTheLimit = SealedCodec.encode(new Data(new byte[MAX_BYTES]));

		assertThat(SealedCodec.decode(SealedCodec.encode(atTheLimit), MAX_BYTES), equalTo(atTheLimit));
		assertThat(refusal(overTheLimit), startsWith("A message of " + (MAX_BYTES + 1) + " bytes exceeds"));
		assertThat(refusal(new byte[0]), startsWith("Truncated"));
	}

	@Property(tries = 1000)
	public void shouldOnlyEverRefuseGarbageAsMalformed(@ForAll @Size(max = 40) byte[] garbage) {
		try {
			SealedCodec.decode(garbage, MAX_BYTES);
		} catch (MalformedMessageException expected) {
			assertThat(expected.getMessage().isEmpty(), is(false));
		}
	}

	@Example
	public void shouldRoundTripEveryKindOfMessage() throws MalformedMessageException {
		for (SealedMessage message : everyKindOfMessage()) {
			assertThat(message.toString(), SealedCodec.decode(SealedCodec.encode(message), MAX_BYTES), equalTo(message));
		}
	}

	@Example
	public void shouldLayOutEveryCommandReplyAndReasonByItsSocks5Byte() {
		assertThat(SealedCodec.encode(new Connect(Socks5CommandType.UDP_ASSOCIATE, IPV4_ADDRESS)),
			equalTo(join(wire(1, 3), IPV4_WIRE)));
		assertThat(SealedCodec.encode(new Connect(Socks5CommandType.CONNECT, IPV6_ADDRESS)),
			equalTo(join(wire(1, 1), IPV6_WIRE)));
		assertThat(SealedCodec.encode(new Datagram(DOMAIN_ADDRESS, PAYLOAD)), equalTo(join(wire(4), DOMAIN_WIRE, PAYLOAD)));
		assertThat(SealedCodec.encode(new Datagram(IPV4_ADDRESS, new byte[0])), equalTo(join(wire(4), IPV4_WIRE)));
		assertThat(SealedCodec.encode(new Data(new byte[0])), equalTo(wire(3)));

		for (int reply = 0; reply <= 8; reply++) {
			assertThat(SealedCodec.encode(new Connected(Socks5CommandStatus.valueOf((byte) reply), DOMAIN_ADDRESS)),
				equalTo(join(wire(2, reply), DOMAIN_WIRE)));
		}

		for (CloseReason reason : CloseReason.values()) {
			assertThat(SealedCodec.encode(new Close(reason)), equalTo(wire(5, reason.code())));
		}
	}

	@Example
	public void shouldWriteAPortBigEndianAndReadItUnsigned() throws MalformedMessageException {
		for (int port : List.of(0, 1, 255, 256, 0x7fff, 0x8000, 0xffff)) {
			final Connect message = new Connect(Socks5CommandType.CONNECT,
				new SocksAddress(Socks5AddressType.IPv4, "192.0.2.1", port));
			final byte[] encoded = wire(1, 1, 1, 192, 0, 2, 1, port >>> Byte.SIZE, port & 0xff);

			assertThat("port " + port, SealedCodec.encode(message), equalTo(encoded));
			assertThat("port " + port, SealedCodec.decode(encoded, MAX_BYTES), equalTo(message));
		}
	}

	@Example
	public void shouldCarryDomainsOfEveryLengthUpToTheLengthByteMaximum() throws MalformedMessageException {
		for (int length : List.of(1, 2, 127, 128, 129, 200, 254, 255)) {
			final Connect message = new Connect(Socks5CommandType.CONNECT,
				new SocksAddress(Socks5AddressType.DOMAIN, "a".repeat(length), 443));
			final byte[] encoded = SealedCodec.encode(message);

			assertThat("length " + length, encoded.length, is(1 + 1 + 1 + 1 + length + 2));
			assertThat("length " + length, encoded[3], is((byte) length));
			assertThat("length " + length, SealedCodec.decode(encoded, MAX_BYTES), equalTo(message));
		}
	}

	@Example
	public void shouldKeepEveryIpv6AddressByteForByte() throws MalformedMessageException {
		final List<byte[]> hosts = List.of(wire(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
			wire(255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255),
			wire(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255, 255, 192, 0, 2, 1), wire(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4),
			wire(0x20, 1, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0xff, 0, 0, 0x42, 0x83, 0x29),
			wire(0, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0), wire(0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1));

		for (byte[] host : hosts) {
			final byte[] encoded = join(wire(1, 1, 4), host, wire(0, 80));
			final Connect decoded = (Connect) SealedCodec.decode(encoded, MAX_BYTES);

			assertThat(Arrays.toString(host), SealedCodec.encode(decoded), equalTo(encoded));
			assertThat(Arrays.toString(host), decoded.destination().type(), equalTo(Socks5AddressType.IPv6));
		}
	}

	@Example
	public void shouldRefuseEveryTruncationOfEveryFixedLengthMessage() {
		for (SealedMessage message : FIXED_LENGTH_MESSAGES) {
			final byte[] encoded = SealedCodec.encode(message);

			for (int cut = 0; cut < encoded.length; cut++) {
				assertThat(message + " cut at " + cut, refusal(Arrays.copyOf(encoded, cut)), startsWith("Truncated"));
			}
		}
	}

	@Example
	public void shouldTakeADatagramCutInItsPayloadAndRefuseOneCutInItsAddress() throws MalformedMessageException {
		for (SocksAddress address : ADDRESSES) {
			final byte[] encoded = SealedCodec.encode(new Datagram(address, PAYLOAD));
			final int addressEnd = encoded.length - PAYLOAD.length;

			for (int cut = 0; cut < addressEnd; cut++) {
				assertThat(address + " cut at " + cut, refusal(Arrays.copyOf(encoded, cut)), startsWith("Truncated"));
			}

			for (int cut = addressEnd; cut <= encoded.length; cut++) {
				assertThat(address + " cut at " + cut, SealedCodec.decode(Arrays.copyOf(encoded, cut), MAX_BYTES),
					equalTo(new Datagram(address, Arrays.copyOf(PAYLOAD, cut - addressEnd))));
			}
		}
	}

	@Example
	public void shouldTakeDataCutAnywhereButBeforeItsTypeByte() throws MalformedMessageException {
		final byte[] encoded = SealedCodec.encode(new Data(PAYLOAD));

		assertThat(refusal(new byte[0]), startsWith("Truncated"));

		for (int cut = 1; cut <= encoded.length; cut++) {
			assertThat("cut at " + cut, SealedCodec.decode(Arrays.copyOf(encoded, cut), MAX_BYTES),
				equalTo(new Data(Arrays.copyOf(PAYLOAD, cut - 1))));
		}
	}

	@Example
	public void shouldRefuseEveryNumberOfBytesAfterAFixedLengthMessage() {
		for (SealedMessage message : FIXED_LENGTH_MESSAGES) {
			for (int extra : List.of(1, 2, 7, 8, 255, 256, 1000)) {
				assertThat(message + " and " + extra, refusal(join(SealedCodec.encode(message), new byte[extra])),
					is("Found " + extra + " bytes after the message"));
			}
		}
	}

	@Example
	public void shouldCountTheBytesLeftOverAfterAMessage() {
		assertThat(refusal(wire(1, 1, 3, 1, 'a', 'b', 0, 80)), is("Found 1 bytes after the message"));
		assertThat(refusal(wire(5, 0, 0)), is("Found 1 bytes after the message"));
	}

	@Example
	public void shouldRefuseALengthBeyondTheBytesThatFollowIt() {
		assertThat(refusal(wire(1, 1, 3, 255, 'a', '.', 'i', 'o', 1, 0xbb)),
			is("Truncated message: needs 255 more bytes, found 6"));
		assertThat(refusal(wire(1, 1, 3, 5, 'a', '.', 'i', 'o')), is("Truncated message: needs 5 more bytes, found 4"));
		assertThat(refusal(wire(1, 1, 3, 4, 'a', '.', 'i', 'o')), is("Truncated message: needs 2 more bytes, found 0"));
		assertThat(refusal(wire(1, 1, 3, 4, 'a', '.', 'i', 'o', 1)), is("Truncated message: needs 2 more bytes, found 1"));
		assertThat(refusal(wire(1, 1, 3, 0x80)), is("Truncated message: needs 128 more bytes, found 0"));
		assertThat(refusal(wire(4, 3, 0xff)), is("Truncated message: needs 255 more bytes, found 0"));
		assertThat(refusal(wire(4, 1, 192, 0, 2)), is("Truncated message: needs 4 more bytes, found 3"));
		assertThat(refusal(wire(2, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)),
			is("Truncated message: needs 16 more bytes, found 15"));
	}

	@Example
	public void shouldRefuseEveryByteThatIsNoMessageType() {
		refuseEveryByteExcept(List.of(1, 2, 3, 4, 5), "Unknown message type",
			value -> join(wire(value), wire(1, 1, 1, 2, 3, 4, 0, 80)));
	}

	@Example
	public void shouldRefuseEveryByteThatIsNoCommandOfASession() {
		refuseEveryByteExcept(List.of(1, 3), "A session carries", value -> join(wire(1, value), IPV4_WIRE));
	}

	@Example
	public void shouldRefuseEveryByteThatIsNoSocks5Reply() {
		refuseEveryByteExcept(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), "SOCKS5 has no reply",
			value -> join(wire(2, value), IPV4_WIRE));
	}

	@Example
	public void shouldRefuseEveryByteThatIsNoCloseReason() {
		refuseEveryByteExcept(List.of(0, 1, 2, 3, 4), "Unknown close reason", value -> wire(5, value));
	}

	@Example
	public void shouldRefuseEveryByteThatIsNoAddressTypeInEveryMessageCarryingOne() {
		refuseEveryByteExcept(List.of(1, 3, 4), "Address type", value -> wire(1, 1, value, 1, 2, 3, 4, 0, 80));
		refuseEveryByteExcept(List.of(1, 3, 4), "Address type", value -> wire(2, 0, value, 1, 2, 3, 4, 0, 80));
		refuseEveryByteExcept(List.of(1, 3, 4), "Address type", value -> wire(4, value, 1, 2, 3, 4, 0, 80, 5));
	}

	@Example
	public void shouldRefuseADomainThatSpellsAnIpAddress() {
		for (String name : List.of("127.0.0.1", "2130706433", "0x7f000001", "0x7f.1", "0177.0.0.1", "127.1", "1.2.3.4.",
			"::1", "[::1]", "fe80::1%eth0", "a:b", "example.com:80")) {
			final String reason = refusal(connectTo(name));

			assertThat(name, reason, startsWith("A domain name"));
			assertThat(name, reason, not(containsString(name)));
		}
	}

	@Example
	public void shouldTakeADomainThatOnlyContainsNumbersBeforeItsLastLabel() throws MalformedMessageException {
		for (String name : List.of("a", "!", "~", "localhost", "127.0.0.1.test", "xn--80ak6aa92e.test", "0x.test")) {
			assertThat(name, SealedCodec.decode(connectTo(name), MAX_BYTES),
				equalTo(new Connect(Socks5CommandType.CONNECT, new SocksAddress(Socks5AddressType.DOMAIN, name, 80))));
		}
	}

	@Example
	public void shouldTakeADomainByteOnlyWithinThePrintableRange() throws MalformedMessageException {
		for (int value : List.of(0x00, 0x1f, 0x20, 0x7f, 0x80, 0xe4, 0xff)) {
			assertThat("byte " + value, refusal(wire(1, 1, 3, 1, value, 0, 80)), startsWith("A domain name"));
		}

		for (int value : List.of(0x21, 0x7e)) {
			assertThat("byte " + value, SealedCodec.decode(wire(1, 1, 3, 1, value, 0, 80), MAX_BYTES),
				equalTo(new Connect(Socks5CommandType.CONNECT, new SocksAddress(Socks5AddressType.DOMAIN,
				String.valueOf((char) value), 80))));
		}
	}

	@Example
	public void shouldCheckTheLimitBeforeLookingAtWhatIsInsideTheMessage() {
		for (int value = 0; value < 256; value++) {
			final byte[] oversized = new byte[MAX_BYTES + 1];

			Arrays.fill(oversized, (byte) value);
			assertThat("filled with " + value, refusal(oversized), is(exceeding(MAX_BYTES + 1, MAX_BYTES)));
		}

		assertThat(refusal(join(SealedCodec.encode(new Close(CloseReason.DONE)), new byte[MAX_BYTES])),
			is(exceeding(MAX_BYTES + 2, MAX_BYTES)));
	}

	@Example
	public void shouldTakeEveryMessageAtItsExactSizeAndRefuseOneByteLess() throws MalformedMessageException {
		for (SealedMessage message : everyKindOfMessage()) {
			final byte[] encoded = SealedCodec.encode(message);

			assertThat(message.toString(), SealedCodec.decode(encoded, encoded.length), equalTo(message));
			assertThat(message.toString(), refusal(encoded, encoded.length - 1),
				is(exceeding(encoded.length, encoded.length - 1)));
		}
	}

	@Example
	public void shouldTakeTheSmallestLimitsThatFitAndRefuseOneBelow() throws MalformedMessageException {
		assertThat(SealedCodec.decode(wire(5, 1), 2), equalTo(new Close(CloseReason.IDLE)));
		assertThat(refusal(wire(5, 1), 1), is(exceeding(2, 1)));
		assertThat(refusal(wire(5, 1), 0), is(exceeding(2, 0)));
		assertThat(refusal(new byte[0], 0), startsWith("Truncated"));
		assertThat(SealedCodec.decode(wire(3), 1), equalTo(new Data(new byte[0])));
	}

	@Example
	public void shouldBoundADataPayloadByTheLimitMinusItsTypeByte() throws MalformedMessageException {
		final int limit = MAX_BYTES - SealedCodec.DATA_OVERHEAD;

		assertThat(SealedCodec.encode(new Data(new byte[5])).length, is(5 + SealedCodec.DATA_OVERHEAD));

		for (int length : List.of(0, 1, limit - 1, limit)) {
			assertThat("length " + length, SealedCodec.decode(SealedCodec.encode(new Data(new byte[length])), MAX_BYTES),
				equalTo(new Data(new byte[length])));
		}

		assertThat(refusal(SealedCodec.encode(new Data(new byte[limit + 1]))), is(exceeding(MAX_BYTES + 1, MAX_BYTES)));
	}

	@Example
	public void shouldBoundADatagramPayloadByTheLimitMinusItsTypeByteAndAddress() throws MalformedMessageException {
		for (SocksAddress address : ADDRESSES) {
			final int limit = MAX_BYTES - SealedCodec.encode(new Datagram(address, new byte[0])).length;

			for (int length : List.of(0, 1, limit - 1, limit)) {
				final Datagram datagram = new Datagram(address, new byte[length]);

				assertThat(address + " length " + length, SealedCodec.decode(SealedCodec.encode(datagram), MAX_BYTES),
					equalTo(datagram));
			}

			assertThat(refusal(SealedCodec.encode(new Datagram(address, new byte[limit + 1]))),
				is(exceeding(MAX_BYTES + 1, MAX_BYTES)));
		}
	}

	@Example
	public void shouldOnlyEverRefuseWithMalformedMessageForEveryShortInput() {
		assertDecodesCanonicallyOrIsRefused(new byte[0], MAX_BYTES);

		for (int first = 0; first < 256; first++) {
			assertDecodesCanonicallyOrIsRefused(wire(first), MAX_BYTES);

			for (int second = 0; second < 256; second++) {
				assertDecodesCanonicallyOrIsRefused(wire(first, second), MAX_BYTES);
			}
		}
	}

	@Example
	public void shouldOnlyEverRefuseWithMalformedMessageForEveryThreeBytesOfAKnownType() {
		for (int type = 1; type <= 5; type++) {
			for (int second = 0; second < 256; second++) {
				for (int third = 0; third < 256; third++) {
					assertDecodesCanonicallyOrIsRefused(wire(type, second, third), MAX_BYTES);
				}
			}
		}
	}

	@Property(tries = 5000, seed = "20251013")
	public void shouldOnlyEverRefuseRandomBytesAsMalformed(@ForAll @Size(max = 64) byte[] garbage,
		@ForAll @IntRange(min = 0, max = 80) int maxBytes) {
		assertDecodesCanonicallyOrIsRefused(garbage, maxBytes);
	}

	@Property(tries = 5000, seed = "20251014")
	public void shouldOnlyEverRefuseTamperedEncodingsAsMalformed(@ForAll("tamperedEncodings") byte[] bytes,
		@ForAll @IntRange(min = 0, max = 400) int maxBytes) {
		assertDecodesCanonicallyOrIsRefused(bytes, maxBytes);
	}
}
