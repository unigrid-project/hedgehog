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

package org.unigrid.hedgehog.proxy.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/* The feeds of every writer on a chain merged into one FIFO. Each writer orders only its own entries,
   so the chain cannot fork. */
public final class Chain {
	private static final Comparator<Entry> FIFO = Comparator.comparingLong((Entry entry) -> entry.header().timestamp())
		.thenComparing(entry -> entry.header().writer()).thenComparingLong(entry -> entry.header().seq());
	private static final Map<Link, Admission> REFUSALS = Map.of(Link.DUPLICATE, Admission.DUPLICATE,
		Link.REPLAYED, Admission.REPLAYED, Link.BROKEN, Admission.BROKEN, Link.FOREIGN, Admission.BROKEN);

	private final ChainId id;
	private final ChainLimits limits;
	private final LongSupplier clock;
	private final Map<WriterId, Feed> feeds = new HashMap<>();
	private final NavigableSet<Entry> entries = new TreeSet<>(FIFO);
	private final Map<WriterId, NavigableMap<Long, Entry>> retained = new HashMap<>();
	private final Map<WriterId, NavigableMap<Long, Entry>> held = new HashMap<>();
	private long bytes;
	private long heldBytes;

	public Chain(ChainId id, ChainLimits limits, LongSupplier clock) {
		this.id = Objects.requireNonNull(id, "id");
		this.limits = Objects.requireNonNull(limits, "limits");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	public ChainId id() {
		return id;
	}

	public ChainLimits limits() {
		return limits;
	}

	public synchronized Offer offer(Entry entry) {
		trim();

		final Optional<Admission> refusal = refusal(entry);

		if (refusal.isPresent()) {
			return Offer.of(refusal.get());
		}

		final WriterId writer = entry.header().writer();
		final Feed feed = feeds.getOrDefault(writer, new Feed(writer));
		final Link link = feed.check(entry);

		if (REFUSALS.containsKey(link)) {
			return Offer.of(REFUSALS.get(link));
		}

		/* The signature is the dear check, so an entry the feed refuses never gets it, only one it takes or holds
		   for a gap does. Entries a chain already has arrive over and over from the other members and from anyone
		   who replays them. */
		if (!entry.isAuthentic()) {
			return Offer.of(Admission.NOT_AUTHENTIC);
		}

		if (link == Link.AHEAD) {
			return hold(entry);
		}

		return isFullFor(entry) ? Offer.of(Admission.FULL) : Offer.accepted(append(feed, entry));
	}

	/* Drops the entries older than the retention window, while every feed keeps its head */
	public synchronized void trim() {
		final long cutoff = clock.getAsLong() - limits.retentionMillis();

		while (!entries.isEmpty() && entries.first().header().timestamp() < cutoff) {
			forget(entries.pollFirst());
		}
	}

	/* Lets a feed join mid-stream after the given head, for a chain whose older entries are trimmed */
	public synchronized boolean anchor(WriterId writer, Head head) {
		return Feed.canStartAfter(head) && feeds.putIfAbsent(writer, new Feed(writer, head)) == null;
	}

	public synchronized List<Entry> entries() {
		return List.copyOf(entries);
	}

	public synchronized List<Entry> range(WriterId writer, long fromSeq, long toSeq) {
		if (fromSeq > toSeq) {
			return List.of();
		}

		return List.copyOf(retained.getOrDefault(writer, Collections.emptyNavigableMap())
			.subMap(fromSeq, true, toSeq, true).values());
	}

	public synchronized Map<WriterId, Head> heads() {
		final Map<WriterId, Head> heads = new HashMap<>();

		feeds.forEach((writer, feed) -> feed.head().ifPresent(head -> heads.put(writer, head)));
		return Map.copyOf(heads);
	}

	public synchronized long bytes() {
		return bytes;
	}

	public synchronized long heldBytes() {
		return heldBytes;
	}

	private Optional<Admission> refusal(Entry entry) {
		final long now = clock.getAsLong();

		if (!id.equals(entry.header().chainId())) {
			return Optional.of(Admission.WRONG_CHAIN);
		}

		if (entry.size() > limits.maxEntryBytes()) {
			return Optional.of(Admission.TOO_LARGE);
		}

		if (entry.header().timestamp() > now + limits.retentionMillis()) {
			return Optional.of(Admission.FUTURE);
		}

		if (entry.header().timestamp() < now - limits.retentionMillis()) {
			return Optional.of(Admission.EXPIRED);
		}

		return Optional.empty();
	}

	/* Only sealed traffic waits for room, so sessions can still open, be accepted and leave */
	private boolean isFullFor(Entry entry) {
		return entry.header().kind() == EntryKind.SEALED && bytes + entry.size() > limits.capacityBytes();
	}

	private Offer hold(Entry entry) {
		if (heldBytes + entry.size() > limits.bufferBytes() || isFullFor(entry)) {
			return Offer.of(Admission.FULL);
		}

		final NavigableMap<Long, Entry> waiting = held.computeIfAbsent(entry.header().writer(),
			writer -> new TreeMap<>());

		if (waiting.putIfAbsent(entry.header().seq(), entry) == null) {
			heldBytes += entry.size();
		}

		return Offer.of(Admission.AHEAD);
	}

	private List<Entry> append(Feed feed, Entry entry) {
		final List<Entry> appended = new ArrayList<>();

		retain(feed, entry, appended);
		release(feed, appended);
		return appended;
	}

	/* Offer.appended is what the chain took in, which entries() may no longer hold: trimming drops the oldest entries,
	   so a non-sealed entry that sorts before everything retained is trimmed as it arrives. Entries released from the
	   buffer are not checked against the capacity again: their writer was already told AHEAD, so dropping them would
	   lose entries, and refusing the filler instead would need its check to count the held run and could answer FULL
	   for good when the capacity is close to maxEntryBytes. bufferBytes() bounds the overshoot */
	private void retain(Feed feed, Entry entry, List<Entry> appended) {
		feed.append(entry);
		feeds.putIfAbsent(feed.writer(), feed);
		entries.add(entry);
		retained.computeIfAbsent(feed.writer(), writer -> new TreeMap<>()).put(entry.header().seq(), entry);
		bytes += entry.size();
		appended.add(entry);
		trimToCapacity();
	}

	/* Appends what waited for the entry just appended and drops what waited but no longer links */
	private void release(Feed feed, List<Entry> appended) {
		final NavigableMap<Long, Entry> waiting = held.getOrDefault(feed.writer(), Collections.emptyNavigableMap());

		while (!waiting.isEmpty() && waiting.firstKey() <= feed.nextSeq()) {
			final Entry next = waiting.pollFirstEntry().getValue();

			heldBytes -= next.size();

			if (feed.check(next) == Link.NEXT) {
				retain(feed, next, appended);
			}
		}

		if (waiting.isEmpty()) {
			held.remove(feed.writer());
		}
	}

	private void trimToCapacity() {
		while (bytes > limits.capacityBytes()) {
			forget(entries.pollFirst());
		}
	}

	private void forget(Entry entry) {
		retained.get(entry.header().writer()).remove(entry.header().seq());
		bytes -= entry.size();
	}
}
