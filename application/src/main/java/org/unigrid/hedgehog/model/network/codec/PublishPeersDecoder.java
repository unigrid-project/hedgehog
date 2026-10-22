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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;

public class PublishPeersDecoder extends AbstractReplayingDecoder<PublishPeers> implements PacketDecoder<PublishPeers> {
	/* An honest peer sends its whole topology, so the entries past the cap are read past rather than refused */
	public static final int MAX_PEERS = 1024;

	/* An IPv6 address written out in full with the name of its scope, the longest an honest peer writes */
	public static final int MAX_HOST_ADDRESS = 64;

	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
	    [ n= num peers ][                   reserved                   ]
	    [ <nodes>                                                  ...n]
	    [     port     ][                   reserved                   ]
	    [    host address                                          ...0]
	*/
	@Override
	public Optional<PublishPeers> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final PublishPeers publishPeers = PublishPeers.builder().build();
		final int numPeers = in.readShort();

		in.skipBytes(6 /* 48 bytes */);

		for (int i = 0; i < numPeers; i++) {
			final int port = in.readUnsignedShort();

			in.skipBytes(6 /* 48 bytes */);

			final String hostAddress = ByteBufUtils.readNullTerminatedString(in, MAX_HOST_ADDRESS);

			if (i < MAX_PEERS) {
				literalOf(hostAddress).ifPresent(address -> publishPeers.getNodes().add(Node.builder()
					.address(new InetSocketAddress(address, port)).build()));
			}
		}

		return Optional.of(publishPeers);
	}

	/* The host is the peer's to name, and looking a name up would hold this stream and every other one served by the
	   same thread for as long as a name server cares to take. An honest peer writes an address, so only an address
	   is taken, and a name is dropped without a lookup. */
	private static Optional<InetAddress> literalOf(String hostAddress) {
		try {
			return Optional.of(InetAddress.ofLiteral(hostAddress));
		} catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.PUBLISH_PEERS;
	}
}
