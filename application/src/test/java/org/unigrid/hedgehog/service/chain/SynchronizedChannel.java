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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;

/* An embedded channel that threads may write to at once, since its event loop is whichever thread calls it. The
   streams of a fleet are these, so that the threads of an end-to-end test can share them. */
final class SynchronizedChannel extends EmbeddedChannel {
	@Override
	public synchronized ChannelFuture write(final Object message) {
		return super.write(message);
	}

	@Override
	public synchronized ChannelFuture write(final Object message, final ChannelPromise promise) {
		return super.write(message, promise);
	}

	@Override
	public synchronized ChannelFuture writeAndFlush(final Object message) {
		return super.writeAndFlush(message);
	}

	@Override
	public synchronized ChannelFuture writeAndFlush(final Object message, final ChannelPromise promise) {
		return super.writeAndFlush(message, promise);
	}

	@Override
	public synchronized Channel flush() {
		return super.flush();
	}

	@Override
	public synchronized <T> T readOutbound() {
		return super.readOutbound();
	}
}
