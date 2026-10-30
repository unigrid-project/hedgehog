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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* The entries of the stem this member holds until they come back to it by flood, as the fail-safe of Dandelion++
   (Fanti et al., 2018) has it: an entry that has not come back by its deadline is flooded by the member itself, so a
   successor that drops what it is passed loses nothing. A deadline is random, a fixed wait and an exponential one of
   the same mean on top. The entries of the member's own writers wait twice as long as those it relays, so that a lost
   entry is almost always flooded by a relay before its author floods it. Those stay held past their deadline, with a
   new one, until they come back, as the replica of the author may have had no room for them when it flooded them. */
final class StemEmbargo {
	static final long RELAY_MILLIS = 2_000;
	static final long OWN_MILLIS = 2 * RELAY_MILLIS;

	/* The most successors one held entry is noted as passed to: the one it was routed to, the one a refusal moved it
	   to, and one more */
	static final int MAX_SENT_TO = 3;

	private static final Comparator<Entry> BY_SEQ = Comparator.comparingLong(entry -> entry.header().seq());

	private record Held(boolean own, long since, long until, Channel source, List<Channel> sentTo) {
		/* Whether the entry is of a writer of this member, since when and until when it waits to come back, the
		   stream it came in on, none for one of this member's own, and the successors it was passed to, the first
		   MAX_SENT_TO of them */
		private Held waitsAgain(final long now, final long deadline) {
			return new Held(own, now, deadline, source, sentTo);
		}

		private Held sent(final Channel successor) {
			if (sentTo.contains(successor) || sentTo.size() >= MAX_SENT_TO) {
				return this;
			}

			final List<Channel> more = new ArrayList<>(sentTo);

			more.add(successor);
			return new Held(own, since, until, source, List.copyOf(more));
		}
	}

	record Due(Entry entry, long since) {
		/* An entry that waited out its deadline, and since when it waited */
	}

	private final RandomGenerator random;
	private final Map<Entry, Held> held = new LinkedHashMap<>();
	private final Map<WriterId, Integer> writers = new HashMap<>();
	private long ownBytes;
	private long relayedBytes;

	StemEmbargo(final RandomGenerator random) {
		this.random = random;
	}

	/* Answers false for an entry that is held already, which is not held again: the stem came round to this member */
	synchronized boolean hold(final Entry entry, final boolean own, final long now) {
		return hold(entry, own, null, now);
	}

	/* The same for an entry that came in on the given stream */
	synchronized boolean hold(final Entry entry, final boolean own, final Channel source, final long now) {
		if (held.containsKey(entry)) {
			return false;
		}

		held.put(entry, new Held(own, now, deadline(own, now), source, List.of()));
		writers.merge(entry.header().writer(), 1, Integer::sum);
		count(own, entry.size());
		return true;
	}

	/* The entry came back, so it waits no more */
	synchronized boolean release(final Entry entry) {
		final Held released = held.remove(entry);

		if (released == null) {
			return false;
		}

		writers.computeIfPresent(entry.header().writer(), (writer, count) -> count > 1 ? count - 1 : null);
		count(released.own(), -entry.size());
		return true;
	}

	synchronized boolean holds(final Entry entry) {
		return held.containsKey(entry);
	}

	/* Notes a successor a held entry was passed to, besides those it was passed to before */
	synchronized void sent(final Entry entry, final Channel successor) {
		held.computeIfPresent(entry, (key, waiting) -> waiting.sent(successor));
	}

	/* Whether a held entry was ever passed to any successor */
	synchronized boolean wasSent(final Entry entry) {
		final Held waiting = held.get(entry);

		return waiting != null && !waiting.sentTo().isEmpty();
	}

	/* Whether a held entry was ever passed to the given stream */
	synchronized boolean wasSentTo(final Entry entry, final Channel stream) {
		final Held waiting = held.get(entry);

		return waiting != null && waiting.sentTo().contains(stream);
	}

	/* The stream a held entry came in on, none for one of this member's own */
	synchronized Optional<Channel> sourceOf(final Entry entry) {
		return Optional.ofNullable(held.get(entry)).map(Held::source);
	}

	/* Whether the entry is held as one of a writer of this member */
	synchronized boolean holdsOwn(final Entry entry) {
		final Held waiting = held.get(entry);

		return waiting != null && waiting.own();
	}

	synchronized boolean holdsAnyOf(final WriterId writer) {
		return writers.containsKey(writer);
	}

	/* The entries of a writer that are held from the given sequence number on, in order */
	synchronized List<Entry> from(final WriterId writer, final long seq) {
		return held.keySet().stream().filter(entry -> entry.header().writer().equals(writer)
			&& entry.header().seq() >= seq).sorted(BY_SEQ).toList();
	}

	/* What waited past its deadline, in the order it was held, for the member to flood */
	synchronized List<Entry> due(final long now) {
		return waited(now).stream().map(Due::entry).toList();
	}

	/* The same, each with since when it waited. Those the member relays are let go, and its own wait again until
	   they come back. */
	synchronized List<Due> waited(final long now) {
		final List<Due> due = new ArrayList<>();

		for (final Map.Entry<Entry, Held> waiting : List.copyOf(held.entrySet())) {
			final Held entry = waiting.getValue();

			if (entry.until() <= now) {
				due.add(new Due(waiting.getKey(), entry.since()));

				if (entry.own()) {
					held.put(waiting.getKey(), entry.waitsAgain(now, deadline(true, now)));
				} else {
					release(waiting.getKey());
				}
			}
		}

		return due;
	}

	/* The bytes of everything held, which the replica would take in once it comes back */
	synchronized long bytes() {
		return ownBytes + relayedBytes;
	}

	/* The bytes held of the writers of this member */
	synchronized long ownBytes() {
		return ownBytes;
	}

	/* The bytes held of the entries this member relays */
	synchronized long relayedBytes() {
		return relayedBytes;
	}

	private void count(final boolean own, final long bytes) {
		if (own) {
			ownBytes += bytes;
		} else {
			relayedBytes += bytes;
		}
	}

	private long deadline(final boolean own, final long now) {
		final long wait = own ? OWN_MILLIS : RELAY_MILLIS;

		return now + wait + Math.round(wait * random.nextExponential());
	}
}
