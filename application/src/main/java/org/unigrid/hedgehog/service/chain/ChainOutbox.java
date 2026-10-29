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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* Where one writer of this node posts its entries: into the chain, through the host. An entry the node's own replica
   has no room for waits in here with every entry written after it, as a feed takes its entries in order, and the
   writer is paused in WriterPauses at its sequence number, as when a member refuses one. The writer is writable
   while nothing waits and nothing pauses it; whoever owns it stops producing meanwhile and hears of a change from
   tick alone. The writers post under the locks of their sessions, which the listener takes too, so the lock of the
   outbox guards its own fields only and is never held while the host or the listener is called. The replica also
   answers FULL for a writer it takes no more of until the epoch changes, so a writer that can never be placed stays
   paused until what waits is too old for the chain, which ends the outbox. */
@Slf4j
@RequiredArgsConstructor
public final class ChainOutbox implements Consumer<Entry> {
	/* Every answer but FULL and those that say the chain holds the entry or is past it. An entry the chain will never
	   take leaves a gap in the feed, which every later entry of the writer waits behind until it expires and breaks
	   every session of the writer. FUTURE is among them though the clock may catch up: the writer holds the latest
	   time it stamped, so after the clock stepped back that far every entry it writes would wait as long as the step,
	   while a new writer stamps the clock as it reads. AHEAD waits past a gap the chain fills or skips without the
	   outbox, and REPLAYED is behind a head that only entries of this writer move, as no anchor names a writer of
	   this node. */
	private static final Set<Admission> ENDS_THE_FEED = EnumSet.complementOf(EnumSet.of(Admission.ACCEPTED,
		Admission.AHEAD, Admission.DUPLICATE, Admission.REPLAYED, Admission.FULL));

	private final ChainHost host;
	private final ChainId chainId;
	private final WriterId writer;
	private final Consumer<Boolean> writability;
	private final Deque<Entry> waiting = new ArrayDeque<>();

	/* Held by whoever posts, accept or tick, so that no entry is posted while an earlier one is on its way */
	private boolean posting;

	/* An entry the chain will never take ends the feed, and so does a chain the host neither hosts nor follows any
	   more, as it may come back with the same id but empty and the feed does not continue on that. The outbox then
	   drops what waits, takes nothing more and stays unwritable: its owner replaces it with the feed of a new writer,
	   and meanwhile its sessions end on their idle limit. */
	private boolean ended;
	private boolean told = true;

	/* Posts at once while nothing waits and nothing is on its way, and queues the entry behind them otherwise */
	@Override
	public void accept(final Entry entry) {
		if (entry != null && claimFor(entry)) {
			postClaimed(entry);
		}
	}

	/* Whatever leaves the host, an Error included, the claim is given back and the entry is offered again: a claim
	   that stayed would keep the writer from ever posting again */
	private void postClaimed(final Entry entry) {
		Admission admission = Admission.FULL;

		try {
			admission = post(entry);
		} finally {
			settlePosted(entry, admission);
		}
	}

	public boolean isWritable() {
		return isClear() && !host.pauses().isPaused(chainId, writer);
	}

	public synchronized boolean hasEnded() {
		return ended;
	}

	/* Whether nothing waits to be placed, whatever pauses the writer. An outbox that ended dropped what waited. */
	public synchronized boolean isDrained() {
		return waiting.isEmpty() && !posting;
	}

	/* Run about once a second, on one thread. What a member refused is offered again before what waited is placed,
	   as lifting the pause of what waited would lift that refusal with it, and what is placed now goes out once. */
	public void tick() {
		host.resend(chainId, writer);
		place();
		announce();
	}

	/* The entry is signed, so one that is lost leaves a gap in the feed that every session of the writer shares: a
	   host that fails is taken to have had no room, and the entry is offered again. An answer that ends the outbox is
	   the last one it gets, as nothing is posted after it, so the log hears of the end once. */
	private Admission post(final Entry entry) {
		Admission admission;

		try {
			admission = host.post(entry);
		} catch (RuntimeException ex) {
			log.warn("Posting an entry of this node failed with {}", ex.getClass().getName());
			admission = Admission.FULL;
		}

		if (admission == Admission.FULL) {
			host.pauses().pause(EntryRefused.of(entry, Admission.FULL));
		} else if (ENDS_THE_FEED.contains(admission)) {
			log.warn("The feed of a writer of this node ends, as its chain answered an entry with {}",
				admission);
		}

		return admission;
	}

	/* Places no more than waited when the tick began, so that a writer that writes on meanwhile cannot keep the tick
	   for good: what it writes waits for the next one */
	private void place() {
		final int waited = claimWaiting();

		if (waited > 0) {
			try {
				placeUpTo(waited);
			} finally {
				release();
			}
		}
	}

	private void placeUpTo(final int waited) {
		int left = waited;

		while (left > 0 && placeFirst()) {
			left--;
		}
	}

	/* The first entry stays in the queue while it is posted, so that the writer does not look writable meanwhile */
	private boolean placeFirst() {
		return settleFirst(post(first()));
	}

	/* The change counts as told once the listener has returned: one that throws is told again by the next tick, as
	   its owner would otherwise never hear of it */
	private void announce() {
		final boolean writable = isWritable();

		if (differsFromTold(writable)) {
			try {
				writability.accept(writable);
				remember(writable);
			} catch (RuntimeException ex) {
				log.warn("Telling the owner of a writer whether it may write failed with {}",
					ex.getClass().getName());
			}
		}
	}

	private synchronized boolean claimFor(final Entry entry) {
		if (ended) {
			return false;
		}

		if (posting || !waiting.isEmpty()) {
			waiting.addLast(entry);
			return false;
		}

		posting = true;
		return true;
	}

	/* An entry without room goes in front of what was queued while it was on its way */
	private synchronized void settlePosted(final Entry entry, final Admission admission) {
		posting = false;

		if (admission == Admission.FULL) {
			waiting.addFirst(entry);
		} else if (ENDS_THE_FEED.contains(admission)) {
			end();
		}
	}

	private synchronized int claimWaiting() {
		if (posting || waiting.isEmpty()) {
			return 0;
		}

		posting = true;
		return waiting.size();
	}

	private synchronized Entry first() {
		return waiting.peekFirst();
	}

	/* Answers whether the entry left the queue and the next may follow it */
	private synchronized boolean settleFirst(final Admission admission) {
		if (ENDS_THE_FEED.contains(admission)) {
			end();
		} else if (admission != Admission.FULL) {
			waiting.pollFirst();
			return true;
		}

		return false;
	}

	private synchronized void release() {
		posting = false;
	}

	private synchronized void end() {
		ended = true;
		waiting.clear();
	}

	private synchronized boolean isClear() {
		return !ended && waiting.isEmpty();
	}

	private synchronized boolean differsFromTold(final boolean writable) {
		return told != writable;
	}

	private synchronized void remember(final boolean writable) {
		told = writable;
	}
}
