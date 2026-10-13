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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.unigrid.hedgehog.proxy.chain.Entry;

/* What a chain appended, waiting to be flooded and told to the listeners in the order the chain appended it. One
   thread at a time drains the queue and nothing is locked while it handles a delivery, so the listeners run on that
   thread only: the thread of a stream that is receiving entries (an event loop, which holds no lock of the host) or
   the delivery thread of the host, never the thread of a caller that posts, since that one may hold locks of its own.
   A thread that finds the queue taken leaves its deliveries to the one that has it. There is no bound on how long
   a thread that drains goes on, as it takes what other threads add meanwhile: the backlog the queue keeps is all that
   caps it, and a receiving stream that happens to drain may be kept at it for as long as the others keep producing. */
final class DeliveryQueue {
	/* The entries one packet or post appended to the chain, the stream they came in on (none for this node's own)
	   and whether the listeners are told of them (not for this node's own) */
	record Delivery(List<Entry> entries, Channel origin, boolean tell) {
		/* One step of the chain's feed on its way out */
	}

	private final OrderedDrain<Delivery> pending;
	private final AtomicBoolean full = new AtomicBoolean();

	/* The queue is a parameter so that a test can bound it or watch it, and decides how much may wait: the
	   thread that adds to a full one is told, and the delivery is not kept */
	DeliveryQueue(final Queue<Delivery> pending) {
		this.pending = new OrderedDrain<>(pending);
	}

	boolean add(final Delivery delivery) {
		return pending.add(delivery);
	}

	/* Answers true for the first delivery dropped since a delivery was last taken from the queue, so that the log
	   hears of a stretch in which the queue stays full once and not once for every delivery that is lost to it */
	boolean beginsFullEpisode() {
		return full.compareAndSet(false, true);
	}

	/* Handles the deliveries one at a time, in order, as OrderedDrain does; a delivery taken from the queue ends a
	   stretch in which it stayed full */
	void drain(final Consumer<Delivery> handler) {
		pending.drain(next -> {
			full.set(false);
			handler.accept(next);
		});
	}
}
