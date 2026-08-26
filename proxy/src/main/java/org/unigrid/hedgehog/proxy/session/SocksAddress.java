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
import java.util.Objects;
import java.util.regex.Pattern;

/* An address as SOCKS5 carries it. An IP literal is kept as Netty's canonical text of its 4 or 16 bytes, so an
   address that went over the wire equals the one that was sent and no resolver can read it another way. A domain
   name is 1 to 255 printable ASCII characters that are not spelt as an IP address and stays unresolved: only the
   exit resolves it, and what a name may resolve to is for the destination policy to judge. */
public record SocksAddress(Socks5AddressType type, String host, int port) {
	public static final int MAX_DOMAIN_LENGTH = 255;
	public static final int MAX_PORT = 0xFFFF;
	public static final SocksAddress UNSPECIFIED = new SocksAddress(Socks5AddressType.IPv4, "0.0.0.0", 0);

	private static final int IPV4_SIZE = 4;
	private static final int IPV6_SIZE = 16;
	private static final char FIRST_PRINTABLE = '!';
	private static final char LAST_PRINTABLE = '~';

	/* No host name holds a colon, a bracket or a percent sign: they mark an IPv6 literal, its brackets and its zone */
	private static final Pattern IPV6_SYNTAX = Pattern.compile("[\\[\\]:%]");

	/* A resolver or URL parser reads a name whose last label is a number as an IPv4 address, in decimal, octal or hex
	   and with fewer than four parts (2130706433, 0177.0.0.1, 0x7f.1, 127.1), and no top-level domain is a number.
	   Trailing dots are skipped so that they cannot hide the number. */
	private static final Pattern ENDS_IN_NUMBER = Pattern.compile("(?:.*\\.)?(?:\\d+|0[xX]\\p{XDigit}*)\\.*");

	public SocksAddress {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(host, "host");
		host = Socks5AddressType.DOMAIN.equals(type) ? domain(host) : literal(type, host);

		if (port < 0 || port > MAX_PORT) {
			throw new IllegalArgumentException("A port is 0 to " + MAX_PORT + ", found " + port);
		}
	}

	public static int sizeOf(Socks5AddressType type) {
		if (Socks5AddressType.IPv4.equals(type)) {
			return IPV4_SIZE;
		}

		if (Socks5AddressType.IPv6.equals(type)) {
			return IPV6_SIZE;
		}

		throw new IllegalArgumentException("Address type " + type + " has no fixed size");
	}

	/* The length of its SOCKS5 form, as a sealed Datagram and a UDP request header carry it: [type u8], then 4 or 16
	   bytes or [length u8][ASCII], then [port u16] */
	public int encodedSize() {
		return Byte.BYTES + (Socks5AddressType.DOMAIN.equals(type) ? Byte.BYTES + host.length() : sizeOf(type))
			+ Short.BYTES;
	}

	private static String literal(Socks5AddressType type, String host) {
		final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(host);

		if (bytes == null || bytes.length != sizeOf(type)) {
			throw new IllegalArgumentException("The host is not an " + type + " address");
		}

		return NetUtil.bytesToIpAddress(bytes);
	}

	private static String domain(String host) {
		if (!isPrintableName(host)) {
			throw new IllegalArgumentException("A domain name is 1 to " + MAX_DOMAIN_LENGTH
				+ " printable ASCII characters, found " + host.length() + " characters");
		}

		if (IPV6_SYNTAX.matcher(host).find() || ENDS_IN_NUMBER.matcher(host).matches()) {
			throw new IllegalArgumentException("A domain name must not be spelt as an IP address");
		}

		return host;
	}

	private static boolean isPrintableName(String host) {
		return !host.isEmpty() && host.length() <= MAX_DOMAIN_LENGTH
			&& host.chars().allMatch(c -> c >= FIRST_PRINTABLE && c <= LAST_PRINTABLE);
	}
}
