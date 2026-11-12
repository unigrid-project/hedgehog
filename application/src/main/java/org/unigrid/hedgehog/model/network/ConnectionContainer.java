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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionContainer implements Connection {
	@Getter protected QuicStreamChannel channel;
	@Getter @Builder.Default protected Optional<EventLoopGroup> group = Optional.empty();

	/* Found through the QUIC connection that both streams belong to. A closed one is not handed out: it stays
	   remembered until the listener on its close has run. */
	@Override
	public Optional<Channel> getChainChannel() {
		return Optional.ofNullable(channel).map(QuicStreamChannel::parent)
			.<Channel>map(connection -> connection.attr(CHAIN_STREAM_KEY).get())
			.filter(Channel::isActive);
	}

	@Override
	public ChannelFuture send(Packet packet) {
		return channel.writeAndFlush(packet);
	}

	/* A connection that came with an event loop is one this node dialed, and is closed before its loop is shut down:
	   the loop takes the socket with it, and a peer that was told nothing of the close holds the connection until it
	   idles out, minutes later. A stream that a peer opened has no loop of its own, and its connection is the
	   server's to close. */
	@SneakyThrows
	public void close() {
		if (!channel.isShutdown()) {
			channel.shutdown().sync();
		}

		if (group.isPresent()) {
			if (!group.get().isShuttingDown()) {
				channel.parent().close().await();
				group.get().shutdownGracefully().sync();
			}
		}
	}

	/* The loop is shut down once the close has gone out, as one shut down at once may close the socket first */
	@SneakyThrows
	public void closeDirty() {
		if (!channel.isShutdown()) {
			channel.shutdown();
		}

		if (group.isPresent()) {
			if (!group.get().isShuttingDown()) {
				channel.parent().close().addListener(closed -> group.get().shutdownGracefully());
			}
		}
	}
}
