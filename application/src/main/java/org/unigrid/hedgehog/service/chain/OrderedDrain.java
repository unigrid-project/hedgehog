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

import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/* What was added, handled in the order it was added and by one thread at a time, with nothing locked while it is
   handled: a thread that adds hands the work to the one that is handling already, or takes it up itself. The queue is
   a parameter so that a test can bound it or watch it, and the bound decides how much may wait. */
final class OrderedDrain<T> {
	private final Queue<T> pending;
	private final AtomicBoolean draining = new AtomicBoolean();

	OrderedDrain(final Queue<T> pending) {
		this.pending = pending;
	}

	/* Answers false when the queue has no room, and the item is not kept */
	boolean add(final T item) {
		return pending.offer(item);
	}

	void clear() {
		pending.clear();
	}

	/* Handles the items one at a time, in order, on the calling thread when no other thread is handling them, and
	   returns at once when one is, as that thread goes on until the queue is empty. The queue is checked again after
	   the claim is given up, since an item added after the last poll found it empty would otherwise wait for the
	   next one. An Error from the handler gives the claim up too, and what is left waits for the next drain. */
	void drain(final Consumer<T> handler) {
		while (!pending.isEmpty() && draining.compareAndSet(false, true)) {
			try {
				for (T next = pending.poll(); next != null; next = pending.poll()) {
					handler.accept(next);
				}
			} finally {
				draining.set(false);
			}
		}
	}
}
