/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

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

import java.util.Arrays;
import org.unigrid.hedgehog.proxy.session.Datagram;
import org.unigrid.hedgehog.proxy.session.MalformedMessageException;
import org.unigrid.hedgehog.proxy.session.SealedCodec;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* The header of a SOCKS5 UDP request, [RSV u16][FRAG u8][address] in front of the data. Its address is laid out as
   in a sealed Datagram, [type u8][address][data], so a header is that message with three zero bytes in place of the
   type byte. Reassembling fragments is optional in SOCKS5 and not done here: FRAG must be zero. */
public final class UdpHeaderCodec {
	public static final int PREFIX_BYTES = 3;
	public static final int MAX_HEADER_BYTES = PREFIX_BYTES + 1 + 1 + SocksAddress.MAX_DOMAIN_LENGTH + 2;

	private static final byte SEALED_TYPE = SealedCodec.encode(new Datagram(SocksAddress.UNSPECIFIED, new byte[0]))[0];

	private UdpHeaderCodec() {
		/* Static helpers only */
	}

	public static byte[] encode(Datagram datagram) {
		final byte[] sealed = SealedCodec.encode(datagram);
		final byte[] packet = new byte[sealed.length - 1 + PREFIX_BYTES];

		System.arraycopy(sealed, 1, packet, PREFIX_BYTES, sealed.length - 1);
		return packet;
	}

	public static Datagram decode(byte[] packet, int maxBytes) throws MalformedDatagramException {
		if (packet.length > maxBytes) {
			throw new MalformedDatagramException("A packet of " + packet.length + " bytes exceeds the limit of "
				+ maxBytes + " bytes");
		}

		requirePrefix(packet);

		final byte[] sealed = Arrays.copyOfRange(packet, PREFIX_BYTES - 1, packet.length);

		sealed[0] = SEALED_TYPE;

		try {
			return (Datagram) SealedCodec.decode(sealed, sealed.length);
		} catch (MalformedMessageException ex) {
			throw new MalformedDatagramException(ex.getMessage());
		}
	}

	private static void requirePrefix(byte[] packet) throws MalformedDatagramException {
		if (packet.length < PREFIX_BYTES) {
			throw new MalformedDatagramException("Truncated header: " + packet.length + " bytes");
		}

		if (packet[0] != 0 || packet[1] != 0) {
			throw new MalformedDatagramException("The reserved field must be zero");
		}

		if (packet[2] != 0) {
			throw new MalformedDatagramException("Fragment " + Byte.toUnsignedInt(packet[2])
				+ " is dropped, fragments are not reassembled");
		}
	}
}
