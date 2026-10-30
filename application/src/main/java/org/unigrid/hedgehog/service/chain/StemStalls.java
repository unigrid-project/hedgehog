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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.util.function.Consumer;

/* Since when a stream to a member has taken none of the stems this node passed it: from the first it did not take,
   for as long as it took none and its writability did not change, which a handler on the stream notes, and which
   hands it what waits for it once it takes writes again. A route counts as stalled for an entry when it took no
   writes for the whole wait of the entry, so that a member cannot spend the replacements of a writer by turning
   unwritable for a moment at a deadline, or when the entry missed StemEmbargo.MISSED_DEADLINES deadlines on it,
   its write there on its way or not, so that a member that seems to take writes but never reads loses the writer
   all the same. */
final class StemStalls {
	private static final AttributeKey<Long> SINCE = AttributeKey.valueOf("stemStallsSince");
	private static final AttributeKey<Boolean> WATCHED = AttributeKey.valueOf("stemStallsWatched");

	private StemStalls() {
	}

	/* The stream took what it was passed */
	static void took(final Channel stream) {
		stream.attr(SINCE).set(null);
	}

	/* The stream took no writes when it was passed stems now, and has taken none since then if it took none before.
	   Once it takes writes again, what waits for it is handed to it. */
	static void missed(final Channel stream, final long now, final Consumer<Channel> writable) {
		watch(stream, writable);
		stream.attr(SINCE).setIfAbsent(now);
	}

	/* Whether the stream has taken no writes from the given time on, without a break */
	static boolean since(final Channel stream, final long time) {
		final Long stalled = stream.attr(SINCE).get();

		return stalled != null && stalled <= time && !stream.isWritable();
	}

	/* A change of writability either way ends the stall noted: a stream that turned writable takes writes again, and
	   one that turned unwritable anew is noted again at the next stem it does not take */
	private static void watch(final Channel stream, final Consumer<Channel> writable) {
		if (stream.attr(WATCHED).setIfAbsent(Boolean.TRUE) == null) {
			stream.pipeline().addFirst(new ChannelInboundHandlerAdapter() {
				@Override
				public void channelWritabilityChanged(final ChannelHandlerContext context) throws Exception {
					context.channel().attr(SINCE).set(null);

					if (context.channel().isWritable()) {
						writable.accept(context.channel());
					}

					super.channelWritabilityChanged(context);
				}
			});
		}
	}
}
