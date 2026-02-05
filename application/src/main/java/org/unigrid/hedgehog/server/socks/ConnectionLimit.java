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

package org.unigrid.hedgehog.server.socks;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import java.util.concurrent.atomic.AtomicInteger;

/* The handshake deadline bounds how long a client holds a connection, not how many it opens: without a limit the
   file descriptors, which the REST and P2P servers share, run out first. Shared by all connections of a listener. */
final class ConnectionLimit extends ChannelInitializer<Channel> {
	private final int max;
	private final ChannelHandler admitted;
	private final AtomicInteger open = new AtomicInteger();

	ConnectionLimit(int max, ChannelHandler admitted) {
		this.max = max;
		this.admitted = admitted;
	}

	@Override
	protected void initChannel(Channel client) {
		if (open.incrementAndGet() > max) {
			open.decrementAndGet();
			client.close();
		} else {
			client.closeFuture().addListener(closed -> open.decrementAndGet());
			client.pipeline().addLast(admitted);
		}
	}
}
