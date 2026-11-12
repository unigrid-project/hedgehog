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

package org.unigrid.hedgehog.transport.initializer;

import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.PeerProtocol;
import org.unigrid.hedgehog.transport.handler.ProtocolMismatchHandler;

/* Sets up each stream a peer opens: the second one carries chain traffic, every other one is a main stream. Stream
   numbers are counted per connection and the number tells who opened a stream, so the number is enough to find the
   second one. A peer of the previous release has no chain stream: every stream it opens is a main one.

   A node opens one main stream on a connection and keeps to it, as did the release before, so a connection is
   served one, the first, for as long as it lives. Each main stream costs the node a pipeline, its schedules and a
   write of every spork that counts, which a peer could ask for again with every stream it opened. A further one is
   closed before anything is set up for it, and a peer that goes on opening them has no use for the connection, which
   is closed with the third. The chain stream is none of them. */
@Slf4j
@RequiredArgsConstructor
public class StreamRouter extends ChannelInitializer<QuicStreamChannel> {
	public static final int REFUSED_STREAMS_TO_CLOSE = 3;

	private static final AttributeKey<Integer> MAIN_STREAMS_KEY = AttributeKey.valueOf("MAIN_STREAMS");

	private final RegisterQuicChannelInitializer main;
	private final ChainStreamInitializer chain;

	@Override
	protected void initChannel(final QuicStreamChannel channel) {
		final ChannelInitializer<QuicStreamChannel> initializer = initializerOf(channel.streamId(),
			PeerProtocol.speaksProxy(channel));
		final int refused = initializer == main ? mainStreamsWith(channel) - 1 : 0;

		if (refused == 0) {
			channel.pipeline().addLast(initializer);
		} else {
			refuse(channel, refused);
		}
	}

	ChannelInitializer<QuicStreamChannel> initializerOf(final long streamId, final boolean speaksProxy) {
		return speaksProxy && streamId == Network.CHAIN_STREAM_ID ? chain : main;
	}

	/* The main streams that the peer has opened on the connection so far, this one among them. A stream is set up on
	   the event loop of its connection, so the count is only touched there. */
	private static int mainStreamsWith(final QuicStreamChannel channel) {
		final Attribute<Integer> counted = channel.parent().attr(MAIN_STREAMS_KEY);
		final int streams = Optional.ofNullable(counted.get()).orElse(0) + 1;

		counted.set(streams);
		return streams;
	}

	/* Closes the stream, and the connection as well once that many streams were refused on it */
	private static void refuse(final QuicStreamChannel channel, final int refused) {
		channel.close();

		if (refused >= REFUSED_STREAMS_TO_CLOSE) {
			final QuicChannel connection = channel.parent();

			log.atDebug().addArgument(() -> peerOf(connection)).addArgument(refused)
				.log("Closed the connection to {}, which opened {} main streams beside its first");
			connection.close();
		}
	}

	private static Object peerOf(final QuicChannel connection) {
		return Optional.<Object>ofNullable(connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get())
			.orElse(connection.remoteAddress());
	}
}
