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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import io.netty.util.NetUtil;
import java.util.Arrays;
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
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

public class UdpHeaderCodecTest {
	private static final int MAX_BYTES = 70_000;
	private static final SocksAddress DNS = new SocksAddress(Socks5AddressType.IPv4, "192.0.2.53", 53);

	private static Arbitrary<String> ipLiterals(int size) {
		return Arbitraries.bytes().array(byte[].class).ofSize(size).map(NetUtil::bytesToIpAddress);
	}

	private static Arbitrary<SocksAddress> addressesOf(Socks5AddressType type, Arbitrary<String> hosts) {
		return Combinators.combine(hosts, Arbitraries.integers().between(0, SocksAddress.MAX_PORT))
			.as((host, port) -> new SocksAddress(type, host, port));
	}

	@Provide
	public Arbitrary<SocksAddress> addresses() {
		final Arbitrary<String> names = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(63)
			.map(label -> label + ".test");

		return Arbitraries.oneOf(addressesOf(Socks5AddressType.IPv4, ipLiterals(4)),
			addressesOf(Socks5AddressType.IPv6, ipLiterals(16)), addressesOf(Socks5AddressType.DOMAIN, names));
	}

	@Provide
	public Arbitrary<Datagram> datagrams() {
		return Combinators.combine(addresses(), Arbitraries.bytes().array(byte[].class).ofMaxSize(2000))
			.as(Datagram::new);
	}

	private static String refusal(byte[] packet) {
		return Expect.assertThrows(MalformedDatagramException.class, () -> UdpHeaderCodec.decode(packet, MAX_BYTES))
			.getMessage();
	}

	@Property(tries = 300)
	public void shouldDecodeWhatItEncoded(@ForAll("datagrams") Datagram datagram) throws MalformedDatagramException {
		assertThat(UdpHeaderCodec.decode(UdpHeaderCodec.encode(datagram), MAX_BYTES), equalTo(datagram));
	}

	@Example
	public void shouldLayTheHeaderOutAsRfc1928Does() {
		assertThat(UdpHeaderCodec.encode(new Datagram(DNS, new byte[] { 7 })),
			equalTo(new byte[] { 0, 0, 0, 1, (byte) 192, 0, 2, 53, 0, 53, 7 }));
		assertThat(UdpHeaderCodec.encode(new Datagram(new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443),
			new byte[] { 1, 2 })), equalTo(new byte[] { 0, 0, 0, 3, 11, 'e', 'x', 'a', 'm', 'p', 'l', 'e', '.',
			'o', 'r', 'g', 1, (byte) 0xbb, 1, 2 }));
		assertThat(UdpHeaderCodec.encode(new Datagram(new SocksAddress(Socks5AddressType.IPv6, "::1", 5353),
			new byte[0])), equalTo(new byte[] { 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0x14,
			(byte) 0xe9 }));
	}

	@Example
	public void shouldNeverNeedMoreThanTheLongestDomainHeaderAndDecodeIt() throws MalformedDatagramException {
		final Datagram longest = new Datagram(new SocksAddress(Socks5AddressType.DOMAIN, "a".repeat(255), 1),
			new byte[0]);
		final byte[] packet = UdpHeaderCodec.encode(longest);

		assertThat(packet.length, is(UdpHeaderCodec.MAX_HEADER_BYTES));
		assertThat(UdpHeaderCodec.decode(packet, MAX_BYTES), equalTo(longest));
	}

	@Property(tries = 50)
	public void shouldDropEveryFragment(@ForAll @IntRange(min = 1, max = 255) int fragment) {
		final byte[] packet = UdpHeaderCodec.encode(new Datagram(DNS, new byte[] { 7 }));

		packet[2] = (byte) fragment;
		assertThat(refusal(packet), startsWith("Fragment " + fragment));
	}

	@Property(tries = 50)
	public void shouldRefuseAReservedFieldThatIsNotZero(@ForAll @IntRange(min = 0, max = 1) int position,
		@ForAll @IntRange(min = 1, max = 255) int value) {

		final byte[] packet = UdpHeaderCodec.encode(new Datagram(DNS, new byte[] { 7 }));

		packet[position] = (byte) value;
		assertThat(refusal(packet), startsWith("The reserved field"));
	}

	@Property(tries = 300)
	public void shouldRefuseEveryTruncatedHeader(@ForAll("addresses") SocksAddress address, @ForAll int cut) {
		final byte[] header = UdpHeaderCodec.encode(new Datagram(address, new byte[0]));

		refusal(Arrays.copyOf(header, Math.floorMod(cut, header.length)));
	}

	@Example
	public void shouldRefuseAnAddressSocksCannotCarry() {
		assertThat(refusal(new byte[] { 0, 0, 0, 2, 1, 2, 3, 4, 0, 80 }), startsWith("Address type"));
		assertThat(refusal(new byte[] { 0, 0, 0, 3, 0, 0, 80 }), startsWith("A domain name"));
		assertThat(refusal(new byte[] { 0, 0, 0, 3, 1, ' ', 0, 80 }), startsWith("A domain name"));
	}

	@Example
	public void shouldRefuseAPacketOverTheBound() throws MalformedDatagramException {
		final byte[] packet = UdpHeaderCodec.encode(new Datagram(DNS, new byte[100]));

		assertThat(UdpHeaderCodec.decode(packet, packet.length).bytes().length, is(100));
		assertThat(Expect.assertThrows(MalformedDatagramException.class,
			() -> UdpHeaderCodec.decode(packet, packet.length - 1)).getMessage(),
			startsWith("A packet of " + packet.length + " bytes exceeds"));
	}

	@Example
	public void shouldRefuseAPacketOverTheBoundBeforeReadingIt() {
		for (byte[] packet : new byte[][] { { 0, 0 }, { 1, 1, 1 }, { 0, 0, 0, 2 } }) {
			assertThat(Expect.assertThrows(MalformedDatagramException.class,
				() -> UdpHeaderCodec.decode(packet, packet.length - 1)).getMessage(),
				startsWith("A packet of"));
		}
	}

	@Example
	public void shouldRefuseOnlyAPrefixThatIsCutShortAsATruncatedHeader() {
		for (int length = 0; length < UdpHeaderCodec.PREFIX_BYTES; length++) {
			assertThat(refusal(new byte[length]), startsWith("Truncated header"));
		}

		assertThat(refusal(new byte[UdpHeaderCodec.PREFIX_BYTES]), not(startsWith("Truncated header")));
	}

	@Example
	public void shouldRefuseTheFieldsOfThePrefixAtTheirBounds() {
		for (int value : new int[] { 1, Byte.MAX_VALUE, Byte.MAX_VALUE + 1, 0xFF }) {
			for (int position = 0; position < UdpHeaderCodec.PREFIX_BYTES; position++) {
				final byte[] packet = UdpHeaderCodec.encode(new Datagram(DNS, new byte[] { 7 }));
				final String reason = position < 2 ? "The reserved field" : "Fragment " + value;

				packet[position] = (byte) value;
				assertThat(refusal(packet), startsWith(reason));
			}
		}
	}

	@Property(tries = 300)
	public void shouldLeaveThePacketItReadsAsItWas(@ForAll("datagrams") Datagram datagram,
		@ForAll @IntRange(min = 0, max = UdpHeaderCodec.PREFIX_BYTES + 1) int at, @ForAll byte flip) {

		final byte[] packet = UdpHeaderCodec.encode(datagram);

		packet[at] ^= flip;

		final byte[] read = packet.clone();

		try {
			UdpHeaderCodec.decode(read, MAX_BYTES);
		} catch (MalformedDatagramException refused) {
			assertThat(refused.getMessage().isEmpty(), is(false));
		}

		assertThat(read, equalTo(packet));
	}

	/* Behind a valid prefix, so that the garbage reaches the address parser and not only the reserved field */
	@Property(tries = 1000)
	public void shouldOnlyEverRefuseGarbageAsMalformed(@ForAll @Size(max = 40) byte[] garbage) {
		final byte[] packet = new byte[UdpHeaderCodec.PREFIX_BYTES + garbage.length];

		System.arraycopy(garbage, 0, packet, UdpHeaderCodec.PREFIX_BYTES, garbage.length);

		try {
			UdpHeaderCodec.decode(packet, MAX_BYTES);
		} catch (MalformedDatagramException expected) {
			assertThat(expected.getMessage().isEmpty(), is(false));
		}
	}
}
