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

package org.unigrid.hedgehog.model.network.initializer;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* The stream that chain traffic has to itself, so that a slow chain flow cannot hold up pings, sporks or storage
   on the main stream. The QUIC connection it belongs to remembers it, which is how the stream of a peer is found
   from the connection the topology holds. QUIC only creates a stream at the peer with its first bytes, so the
   client opens it with a ping, which the server answers. */
@RequiredArgsConstructor
public class ChainStreamInitializer extends ChannelInitializer<QuicStreamChannel> {
	private final RegisterQuicChannelInitializer.Type type;

	@Override
	protected void initChannel(final QuicStreamChannel channel) {
		final Attribute<QuicStreamChannel> remembered = channel.parent().attr(Connection.CHAIN_STREAM_KEY);

		remembered.set(channel);
		channel.closeFuture().addListener(closed -> remembered.compareAndSet(channel, null));
		channel.pipeline().addLast(ChainPipeline.streamHandlers().toArray(new ChannelHandler[0]));

		if (type == RegisterQuicChannelInitializer.Type.CLIENT) {
			channel.writeAndFlush(Ping.builder().build());
		}
	}
}
