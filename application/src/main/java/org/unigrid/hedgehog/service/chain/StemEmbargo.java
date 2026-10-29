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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

	private static final Comparator<Entry> BY_SEQ = Comparator.comparingLong(entry -> entry.header().seq());

	private record Held(boolean own, long until) {
		/* Whether the entry is of a writer of this member, and until when it waits to come back */
	}

	private final RandomGenerator random;
	private final Map<Entry, Held> held = new LinkedHashMap<>();
	private final Map<WriterId, Integer> writers = new HashMap<>();
	private long bytes;

	StemEmbargo(final RandomGenerator random) {
		this.random = random;
	}

	/* Answers false for an entry that is held already, which is not held again: the stem came round to this member */
	synchronized boolean hold(final Entry entry, final boolean own, final long now) {
		if (held.containsKey(entry)) {
			return false;
		}

		held.put(entry, new Held(own, deadline(own, now)));
		writers.merge(entry.header().writer(), 1, Integer::sum);
		bytes += entry.size();
		return true;
	}

	/* The entry came back, so it waits no more */
	synchronized boolean release(final Entry entry) {
		if (held.remove(entry) == null) {
			return false;
		}

		writers.computeIfPresent(entry.header().writer(), (writer, count) -> count > 1 ? count - 1 : null);
		bytes -= entry.size();
		return true;
	}

	synchronized boolean holds(final Entry entry) {
		return held.containsKey(entry);
	}

	/* Whether the entry is held as one of a writer of this member */
	synchronized boolean holdsOwn(final Entry entry) {
		final Held waiting = held.get(entry);

		return waiting != null && waiting.own();
	}

	synchronized boolean holdsAnyOf(final WriterId writer) {
		return writers.containsKey(writer);
	}

	/* The entries of a writer of this member that are held from the given sequence number on, in order */
	synchronized List<Entry> ownFrom(final WriterId writer, final long seq) {
		return held.entrySet().stream().filter(waiting -> waiting.getValue().own())
			.map(Map.Entry::getKey).filter(entry -> entry.header().writer().equals(writer)
			&& entry.header().seq() >= seq).sorted(BY_SEQ).toList();
	}

	/* What waited past its deadline, in the order it was held, for the member to flood. Those it relays are let go,
	   and its own wait again until they come back. */
	synchronized List<Entry> due(final long now) {
		final List<Entry> due = new ArrayList<>();

		for (final Map.Entry<Entry, Held> waiting : List.copyOf(held.entrySet())) {
			final Held entry = waiting.getValue();

			if (entry.until() <= now) {
				due.add(waiting.getKey());

				if (entry.own()) {
					held.put(waiting.getKey(), new Held(true, deadline(true, now)));
				} else {
					release(waiting.getKey());
				}
			}
		}

		return due;
	}

	/* The bytes of everything held, which the replica would take in once it comes back */
	synchronized long bytes() {
		return bytes;
	}

	private long deadline(final boolean own, final long now) {
		final long wait = own ? OWN_MILLIS : RELAY_MILLIS;

		return now + wait + Math.round(wait * random.nextExponential());
	}
}
