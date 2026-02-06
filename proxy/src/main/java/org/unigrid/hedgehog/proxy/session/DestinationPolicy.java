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
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/* Keeps an exit off its own host and networks: it refuses loopback, unspecified, private (RFC 1918 and
   fc00::/7), shared (100.64/10), link-local, site-local, multicast, reserved, benchmarking, documentation and
   local-use NAT64 addresses, and the IPv4 inside IPv4-mapped, IPv4-translated, IPv4-compatible, NAT64, 6to4,
   Teredo and ISATAP IPv6 addresses is held to the IPv4 rules. It is the only gate: an exit's own ports, and those
   of other gridnodes, are out of reach only because their addresses are. */
public final class DestinationPolicy {
	private static final int IPV4_SIZE = 4;

	private static final Block IPV4_LOOPBACK = block("127.0.0.0", 8);
	private static final Block IPV6_LOOPBACK = block("::1", 128);
	private static final List<Block> REFUSED_IPV4 = List.of(block("0.0.0.0", 8), block("10.0.0.0", 8),
		block("100.64.0.0", 10), IPV4_LOOPBACK, block("169.254.0.0", 16), block("172.16.0.0", 12),
		block("192.0.0.0", 24), block("192.0.2.0", 24), block("192.168.0.0", 16), block("198.18.0.0", 15),
		block("198.51.100.0", 24), block("203.0.113.0", 24), block("224.0.0.0", 4), block("240.0.0.0", 4));
	private static final List<Block> REFUSED_IPV6 = List.of(block("::", 128), IPV6_LOOPBACK, block("fc00::", 7),
		block("fe80::", 10), block("fec0::", 10), block("ff00::", 8), block("2001:db8::", 32),
		block("64:ff9b:1::", 48));
	private static final Block TEREDO = block("2001::", 32);

	/* The interface identifier of an ISATAP address, under any prefix, is 0000:5efe or, for a global IPv4 address,
	   0200:5efe, and the IPv4 address of the tunnel follows it */
	private static final byte[] ISATAP = { 0x00, 0x00, 0x5e, (byte) 0xfe };
	private static final byte[] ISATAP_GLOBAL = { 0x02, 0x00, 0x5e, (byte) 0xfe };
	private static final int ISATAP_OFFSET = 8;
	private static final List<Embedding> EMBEDDED_IPV4 = List.of(Embedding.in(block("::ffff:0:0", 96), 12, false),
		Embedding.in(block("::ffff:0:0:0", 96), 12, false), Embedding.in(block("::", 96), 12, false),
		Embedding.in(block("64:ff9b::", 96), 12, false), Embedding.in(block("2002::", 16), 2, false),
		Embedding.in(TEREDO, 4, false), Embedding.in(TEREDO, 12, true),
		new Embedding(DestinationPolicy::isIsatap, 12, false));

	/* Resolvers answer localhost and every name below it with the local host. Trailing dots are skipped so that
	   they cannot hide the name. */
	private static final Pattern LOCALHOST = Pattern.compile("(?:.*\\.)?localhost\\.*", Pattern.CASE_INSENSITIVE);

	private static final DestinationPolicy PUBLIC_ONLY = new DestinationPolicy(false);
	private static final DestinationPolicy LOOPBACK_ALLOWED = new DestinationPolicy(true);

	private final boolean loopbackAllowed;

	private record Block(byte[] prefix, int bits) {
		boolean contains(byte[] address) {
			final int whole = bits / Byte.SIZE;
			final int partialMask = 0xff << (Byte.SIZE - bits % Byte.SIZE) & 0xff;

			return address.length == prefix.length && Arrays.equals(address, 0, whole, prefix, 0, whole)
				&& (whole == prefix.length || ((address[whole] ^ prefix[whole]) & partialMask) == 0);
		}
	}

	/* An IPv4 address carried inside an IPv6 one; Teredo stores its client address with every bit flipped */
	private record Embedding(Predicate<byte[]> carries, int offset, boolean flipped) {
		static Embedding in(Block block, int offset, boolean flipped) {
			return new Embedding(block::contains, offset, flipped);
		}

		byte[] ipv4(byte[] address) {
			final byte[] ipv4 = Arrays.copyOfRange(address, offset, offset + IPV4_SIZE);

			for (int i = 0; flipped && i < ipv4.length; i++) {
				ipv4[i] = (byte) ~ipv4[i];
			}

			return ipv4;
		}
	}

	private DestinationPolicy(boolean loopbackAllowed) {
		this.loopbackAllowed = loopbackAllowed;
	}

	public static DestinationPolicy publicOnly() {
		return PUBLIC_ONLY;
	}

	/* For tests that reach targets on the local host; every other refusal stays */
	public static DestinationPolicy allowLoopback() {
		return LOOPBACK_ALLOWED;
	}

	public boolean allows(InetAddress address) {
		final byte[] bytes = address.getAddress();

		return bytes.length == IPV4_SIZE ? allowsIpv4(bytes) : allowsIpv6(bytes);
	}

	/* The addresses an exit may connect to: an IP literal as given, a domain name as resolved. A name is
	   refused as a whole when any of its addresses is, so it cannot pair a public answer with a local one.
	   The exit connects to these addresses and never resolves the name again. */
	public List<InetAddress> admit(SocksAddress destination, Resolver resolver) throws DestinationRefusedException {
		if (isLocalName(destination)) {
			throw new DestinationRefusedException(Socks5CommandStatus.FORBIDDEN, destination.host()
				+ " is not a public name");
		}

		final List<InetAddress> addresses = resolve(destination, resolver);

		if (addresses.isEmpty()) {
			throw new DestinationRefusedException(Socks5CommandStatus.HOST_UNREACHABLE, destination.host()
				+ " has no address");
		}

		if (!addresses.stream().allMatch(this::allows)) {
			throw new DestinationRefusedException(Socks5CommandStatus.FORBIDDEN, destination.host()
				+ " is not a public destination");
		}

		return addresses;
	}

	/* Refused before the lookup, since the name says what it is. An IP literal never matches the pattern, and a
	   name spelt as an IP address never gets here: SocksAddress refuses to build it. */
	private boolean isLocalName(SocksAddress destination) {
		return !loopbackAllowed && LOCALHOST.matcher(destination.host()).matches();
	}

	private static List<InetAddress> resolve(SocksAddress destination, Resolver resolver)
		throws DestinationRefusedException {
		try {
			if (Socks5AddressType.DOMAIN.equals(destination.type())) {
				return List.copyOf(resolver.resolveAll(destination.host()));
			}

			final byte[] literal = NetUtil.createByteArrayFromIpAddressString(destination.host());

			return List.of(InetAddress.getByAddress(literal));
		} catch (UnknownHostException ex) {
			throw new DestinationRefusedException(Socks5CommandStatus.HOST_UNREACHABLE, destination.host()
				+ " does not resolve");
		}
	}

	private boolean allowsIpv4(byte[] address) {
		return loopbackAllowed && IPV4_LOOPBACK.contains(address)
			|| REFUSED_IPV4.stream().noneMatch(block -> block.contains(address));
	}

	private boolean allowsIpv6(byte[] address) {
		if (loopbackAllowed && IPV6_LOOPBACK.contains(address)) {
			return true;
		}

		if (REFUSED_IPV6.stream().anyMatch(block -> block.contains(address))) {
			return false;
		}

		return EMBEDDED_IPV4.stream().filter(embedding -> embedding.carries().test(address))
			.allMatch(embedding -> allowsIpv4(embedding.ipv4(address)));
	}

	private static boolean isIsatap(byte[] address) {
		final int end = ISATAP_OFFSET + ISATAP.length;

		return Arrays.equals(address, ISATAP_OFFSET, end, ISATAP, 0, ISATAP.length)
			|| Arrays.equals(address, ISATAP_OFFSET, end, ISATAP_GLOBAL, 0, ISATAP_GLOBAL.length);
	}

	private static Block block(String prefix, int bits) {
		return new Block(NetUtil.createByteArrayFromIpAddressString(prefix), bits);
	}
}
