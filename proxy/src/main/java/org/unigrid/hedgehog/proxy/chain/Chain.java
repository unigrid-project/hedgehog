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
	private static final Map<Link, Admission> REFUSALS = Map.of(Link.AHEAD, Admission.AHEAD,
		Link.DUPLICATE, Admission.DUPLICATE, Link.REPLAYED, Admission.REPLAYED,
		Link.BROKEN, Admission.BROKEN, Link.FOREIGN, Admission.BROKEN);

	private final ChainId id;
	private final ChainLimits limits;
	private final LongSupplier clock;
	private final Map<WriterId, Feed> feeds = new HashMap<>();
	private final NavigableSet<Entry> entries = new TreeSet<>(FIFO);
	private final Map<WriterId, NavigableMap<Long, Entry>> retained = new HashMap<>();
	private long bytes;

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

		if (link != Link.NEXT) {
			return Offer.of(REFUSALS.get(link));
		}

		/* The signature is the dear check, so only an entry the feed could take gets it. Entries a chain
		   already has arrive over and over from the other members and from anyone who replays them. */
		if (!entry.isAuthentic()) {
			return Offer.of(Admission.NOT_AUTHENTIC);
		}

		return Offer.accepted(List.of(append(feed, entry)));
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

	private Entry append(Feed feed, Entry entry) {
		feed.append(entry);
		feeds.putIfAbsent(feed.writer(), feed);
		entries.add(entry);
		retained.computeIfAbsent(feed.writer(), writer -> new TreeMap<>()).put(entry.header().seq(), entry);
		bytes += entry.size();
		trimToCapacity();
		return entry;
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
