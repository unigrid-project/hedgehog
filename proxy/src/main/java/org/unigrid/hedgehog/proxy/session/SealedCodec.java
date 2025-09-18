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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/* [type u8] then CONNECT (1): [command u8][address], CONNECTED (2): [status u8][address], DATA (3): [bytes],
   DATAGRAM (4): [address][bytes], CLOSE (5): [reason u8]. An address is [type u8] then 4 bytes for IPv4, 16
   for IPv6 or [length u8][ASCII] for a domain, then [port u16], as in SOCKS5. Big-endian throughout. */
public final class SealedCodec {
	public static final int DATA_OVERHEAD = 1;

	private static final int CONNECT = 1;
	private static final int CONNECTED = 2;
	private static final int DATA = 3;
	private static final int DATAGRAM = 4;
	private static final int CLOSE = 5;

	private SealedCodec() {
		/* Static helpers only */
	}

	public static byte[] encode(SealedMessage message) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		if (message instanceof Connect connect) {
			out.write(CONNECT);
			out.write(connect.command().byteValue());
			writeAddress(out, connect.destination());
		} else if (message instanceof Connected connected) {
			out.write(CONNECTED);
			out.write(connected.status().byteValue());
			writeAddress(out, connected.bound());
		} else if (message instanceof Data data) {
			out.write(DATA);
			out.writeBytes(data.bytes());
		} else if (message instanceof Datagram datagram) {
			out.write(DATAGRAM);
			writeAddress(out, datagram.address());
			out.writeBytes(datagram.bytes());
		} else {
			out.write(CLOSE);
			out.write(((Close) message).reason().code());
		}

		return out.toByteArray();
	}

	public static SealedMessage decode(byte[] bytes, int maxBytes) throws MalformedMessageException {
		if (bytes.length > maxBytes) {
			throw new MalformedMessageException("A message of " + bytes.length + " bytes exceeds the limit of "
				+ maxBytes + " bytes");
		}

		final ByteBuffer in = ByteBuffer.wrap(bytes);

		try {
			final SealedMessage message = body(take(in), in);

			if (in.hasRemaining()) {
				throw new MalformedMessageException("Found " + in.remaining() + " bytes after the message");
			}

			return message;
		} catch (IllegalArgumentException ex) {
			throw new MalformedMessageException(ex.getMessage());
		}
	}

	private static SealedMessage body(byte type, ByteBuffer in) throws MalformedMessageException {
		switch (type) {
			case CONNECT:
				return new Connect(Socks5CommandType.valueOf(take(in)), address(in));
			case CONNECTED:
				return new Connected(Socks5CommandStatus.valueOf(take(in)), address(in));
			case DATA:
				return new Data(rest(in));
			case DATAGRAM:
				return new Datagram(address(in), rest(in));
			case CLOSE:
				return close(take(in));
			default:
				throw new MalformedMessageException("Unknown message type " + type);
		}
	}

	private static void writeAddress(ByteArrayOutputStream out, SocksAddress address) {
		out.write(address.type().byteValue());

		if (Socks5AddressType.DOMAIN.equals(address.type())) {
			out.write(address.host().length());
			out.writeBytes(address.host().getBytes(StandardCharsets.US_ASCII));
		} else {
			out.writeBytes(NetUtil.createByteArrayFromIpAddressString(address.host()));
		}

		out.write(address.port() >>> Byte.SIZE);
		out.write(address.port());
	}

	private static SocksAddress address(ByteBuffer in) throws MalformedMessageException {
		final Socks5AddressType type = Socks5AddressType.valueOf(take(in));
		final boolean domain = Socks5AddressType.DOMAIN.equals(type);
		final byte[] host = bytes(in, domain ? Byte.toUnsignedInt(take(in)) : SocksAddress.sizeOf(type));

		requireRemaining(in, Short.BYTES);
		return new SocksAddress(type, domain ? new String(host, StandardCharsets.US_ASCII)
			: NetUtil.bytesToIpAddress(host), Short.toUnsignedInt(in.getShort()));
	}

	private static Close close(byte code) throws MalformedMessageException {
		return new Close(CloseReason.of(code).orElseThrow(() -> new MalformedMessageException("Unknown close reason "
			+ code)));
	}

	private static byte take(ByteBuffer in) throws MalformedMessageException {
		requireRemaining(in, Byte.BYTES);
		return in.get();
	}

	private static byte[] bytes(ByteBuffer in, int size) throws MalformedMessageException {
		final byte[] bytes = new byte[size];

		requireRemaining(in, size);
		in.get(bytes);
		return bytes;
	}

	private static byte[] rest(ByteBuffer in) throws MalformedMessageException {
		return bytes(in, in.remaining());
	}

	private static void requireRemaining(ByteBuffer in, int size) throws MalformedMessageException {
		if (in.remaining() < size) {
			throw new MalformedMessageException("Truncated message: needs " + size + " more bytes, found "
				+ in.remaining());
		}
	}
}
