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

package org.unigrid.hedgehog.ledger;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/* How high the peers say their chains are, and whether this node is fetching blocks from one of them. What a peer
   says is a claim and proves nothing, as a status is not signed: only certified blocks are proof. A claim makes a
   peer someone to ask, and nothing in it says who is asked first. It is dropped for a peer that is asked and cannot
   back it up, and that peer is not believed for a while after, so that a peer which claims a chain it has not got
   holds nothing up for longer than one request, and cannot do it again at no cost. A claim that is not repeated
   lapses, and a claim of a peer that is not connected any more is not counted, so a peer that has left stops being
   waited for. The most claims and marks kept are limited, and a peer that speaks again takes the place of its last
   claim and goes to the back. Used by the driver on its serial executor only. */
final class SyncState {
	static final int MAX_CLAIMS = 256;
	static final long MEMORY_MILLIS = 600_000;
	private static final int MAX_DOUBLINGS = 20;
	private static final int FIRST_SHIFT = 30;
	private static final int SECOND_SHIFT = 27;
	private static final int THIRD_SHIFT = 31;
	private static final long FIRST_FACTOR = 0xbf58476d1ce4e5b9L;
	private static final long SECOND_FACTOR = 0x94d049bb133111ebL;

	record Claim(LedgerPeer peer, long height, long heardAt) {
	}

	private final long lapseMillis;
	private final Supplier<Set<String>> present;
	private final Map<String, Claim> claims = new LinkedHashMap<>();
	private final Map<String, Long> marks = new LinkedHashMap<>();
	private final Map<String, Integer> strikes = new LinkedHashMap<>();
	private final Map<String, Long> deliveredAt = new LinkedHashMap<>();
	private final long salt;
	private long deliveries;
	private boolean retried;
	private boolean running;
	private boolean holding;
	private long startHeight;

	/* `present` is the ids of the peers that are connected now, and `salt` is what the order of peers that nothing
	   else tells apart depends on */
	SyncState(long lapseMillis, Supplier<Set<String>> present, long salt) {
		this.lapseMillis = lapseMillis;
		this.present = present;
		this.salt = salt;
	}

	void heard(LedgerPeer peer, long height, long now) {
		final String id = peer.id();

		claims.values().removeIf(claim -> lapsed(claim, now));
		claims.remove(id);
		claims.put(id, new Claim(peer, height, now));

		if (claims.size() > MAX_CLAIMS) {
			claims.remove(claims.keySet().iterator().next());
		}
	}

	void drop(String id) {
		claims.remove(id);
	}

	/* The peer could not back its claim or served what is not right: what it says is not counted for ten minutes */
	void discredit(String id, long now) {
		mark(id, now, MEMORY_MILLIS);
	}

	/* A request got no answer, which is the network's trouble as likely as the peer's: the peer is doubted for
	   `unitMillis`, doubling with each failure in a row up to ten minutes, and a batch that it delivers ends the row */
	void failed(String id, long now, long unitMillis) {
		final int row = strikes.merge(id, 1, Integer::sum);

		bound(strikes);
		mark(id, now, Math.min(MEMORY_MILLIS, unitMillis << Math.min(row - 1, MAX_DOUBLINGS)));
	}

	void delivered(String id) {
		strikes.remove(id);
		retried = false;
		deliveredAt.remove(id);
		deliveredAt.put(id, ++deliveries);
		bound(deliveredAt);
	}

	/* True the first time since a batch was delivered, which is when a request that failed is asked again */
	boolean retryOnce() {
		final boolean first = !retried;

		retried = true;
		return first;
	}

	private void mark(String id, long now, long millis) {
		final long until = Math.max(now + millis, marks.getOrDefault(id, 0L));

		marks.values().removeIf(end -> now >= end);
		marks.remove(id);
		marks.put(id, until);
		bound(marks);
	}

	private static void bound(Map<String, ?> table) {
		if (table.size() > MAX_CLAIMS) {
			table.remove(table.keySet().iterator().next());
		}
	}

	/* A clock that was set back makes nothing pass */
	boolean discredited(String id, long now) {
		final Long until = marks.get(id);

		return until != null && now < until;
	}

	OptionalLong highest(long now) {
		return standing(now).mapToLong(Claim::height).max();
	}

	/* The peer to ask next of those that claim a chain above the height. The claim only makes a peer a candidate: how
	   little or how much it claims is a peer's own choice, and would put it first. What a peer cannot choose decides
	   the order: first one that this node dialled, then the ones that have delivered a batch, the latest to have done
	   so first, and then the rest in the order of a hash of the id with the salt, which an id cannot be made up to
	   win as it does not know the salt. */
	Optional<Claim> firstAbove(long height, long now) {
		return standing(now).filter(claim -> claim.height() > height).min(Comparator
			.comparing((Claim claim) -> !claim.peer().dialled())
			.thenComparingLong(claim -> -deliveredAt.getOrDefault(claim.peer().id(), 0L))
			.thenComparingLong(claim -> ranked(claim.peer().id()))
			.thenComparing(claim -> claim.peer().id()));
	}

	private long ranked(String id) {
		long hash = salt;

		for (int n = 0; n < id.length(); n++) {
			hash = mixed(hash ^ id.charAt(n));
		}

		return hash;
	}

	/* The finishing step of splitmix64: a change in any bit of the input changes about half of the bits of the output */
	private static long mixed(long value) {
		final long first = (value ^ (value >>> FIRST_SHIFT)) * FIRST_FACTOR;
		final long second = (first ^ (first >>> SECOND_SHIFT)) * SECOND_FACTOR;

		return second ^ (second >>> THIRD_SHIFT);
	}

	boolean claimsAbove(String id, long height, long now) {
		return standing(now).anyMatch(claim -> claim.peer().id().equals(id) && claim.height() > height);
	}

	/* Whether a sync is waiting for an answer, or its last batch moved the ledger: that is what holds back the vote of
	   a node after the grace. A sync that gets no answer, or none that is right, holds nothing back. */
	boolean holdsBack() {
		return running && holding;
	}

	void stopHolding() {
		holding = false;
	}

	void progressed() {
		holding = true;
	}

	/* Nothing is waited for and nobody is believed or doubted any more: for a driver that has stopped */
	void clear() {
		running = false;
		holding = false;
		retried = false;
		claims.clear();
		marks.clear();
		strikes.clear();
		deliveredAt.clear();
	}

	boolean running() {
		return running;
	}

	void begin(long height) {
		running = true;
		holding = true;
		retried = false;
		startHeight = height;
	}

	/* The height the ledger was at when the sync began */
	long end() {
		running = false;
		return startHeight;
	}

	/* The claims that have not lapsed, of peers that are connected now: a peer that left holds nothing up */
	private Stream<Claim> standing(long now) {
		if (claims.isEmpty()) {
			return Stream.empty();
		}

		final Set<String> connected = present.get();

		return claims.values().stream()
			.filter(claim -> !lapsed(claim, now) && connected.contains(claim.peer().id()));
	}

	/* A clock that was set back makes nothing lapse */
	private boolean lapsed(Claim claim, long now) {
		return now - claim.heardAt() >= lapseMillis;
	}
}
