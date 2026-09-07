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

package org.unigrid.hedgehog.model.network.initializer;

import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.Network;

/* Sets up each stream a peer opens: the second one carries chain traffic, every other one is a main stream. Stream
   numbers are counted per connection and the number tells who opened a stream, so the number alone is enough. */
@RequiredArgsConstructor
public class StreamRouter extends ChannelInitializer<QuicStreamChannel> {
	private final RegisterQuicChannelInitializer main;
	private final ChainStreamInitializer chain;

	@Override
	protected void initChannel(final QuicStreamChannel channel) {
		channel.pipeline().addLast(initializerOf(channel.streamId()));
	}

	ChannelInitializer<QuicStreamChannel> initializerOf(final long streamId) {
		return streamId == Network.CHAIN_STREAM_ID ? chain : main;
	}
}
