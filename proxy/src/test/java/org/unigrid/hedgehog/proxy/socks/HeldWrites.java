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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;

/* An embedded channel completes every write at once; this holds them back until the test lets them through, as
   a socket that has not sent them yet would, so that a close that does not wait for them shows */
final class HeldWrites extends ChannelOutboundHandlerAdapter {
	private final List<Runnable> held = new ArrayList<>();

	@Override
	public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
		held.add(() -> ctx.writeAndFlush(message, promise));
	}

	/* A write that completes may queue another, so the held ones are taken off the list before they run */
	void release() {
		final List<Runnable> now = new ArrayList<>(held);

		held.clear();
		now.forEach(Runnable::run);
	}
}
