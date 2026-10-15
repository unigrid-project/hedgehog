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
import java.util.stream.Stream;

/* How high the peers say their chains are, and whether this node is fetching blocks from one of them. What a peer
   says is a claim and proves nothing, as a status is not signed: only certified blocks are proof. A claim tells
   whom to ask. It is dropped for a peer that is asked and cannot back it up, and that peer is not believed for a
   while after, so that a peer which claims a chain it has not got holds nothing up for longer than one request, and
   cannot do it again at no cost. A claim that is not repeated lapses, which is also how a peer that has left stops
   being waited for: the driver is told of no disconnect. The most claims and marks kept are limited, and a peer that
   speaks again takes the place of its last claim and goes to the back. Used by the driver on its serial executor
   only. */
final class SyncState {
	static final int MAX_CLAIMS = 256;
	static final long MEMORY_MILLIS = 600_000;

	record Claim(LedgerPeer peer, long height, long heardAt) {
	}

	private final long lapseMillis;
	private final Map<String, Claim> claims = new LinkedHashMap<>();
	private final Map<String, Long> discredited = new LinkedHashMap<>();
	private boolean running;
	private long startHeight;

	SyncState(long lapseMillis) {
		this.lapseMillis = lapseMillis;
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

	/* The peer could not back its claim: what it says is not counted until the memory of it has passed */
	void discredit(String id, long now) {
		discredited.values().removeIf(marked -> now - marked >= MEMORY_MILLIS);
		discredited.remove(id);
		discredited.put(id, now);

		if (discredited.size() > MAX_CLAIMS) {
			discredited.remove(discredited.keySet().iterator().next());
		}
	}

	/* A clock that was set back makes nothing pass */
	boolean discredited(String id, long now) {
		final Long marked = discredited.get(id);

		return marked != null && now - marked < MEMORY_MILLIS;
	}

	OptionalLong highest(long now) {
		return standing(now).mapToLong(Claim::height).max();
	}

	Optional<Claim> furthestAbove(long height, long now) {
		return standing(now).filter(claim -> claim.height() > height).max(Comparator.comparingLong(Claim::height));
	}

	boolean claimsAbove(String id, long height, long now) {
		return Optional.ofNullable(claims.get(id)).filter(claim -> !lapsed(claim, now) && claim.height() > height)
			.isPresent();
	}

	/* Nothing is waited for and nobody is believed or doubted any more: for a driver that has stopped */
	void clear() {
		running = false;
		claims.clear();
		discredited.clear();
	}

	boolean running() {
		return running;
	}

	void begin(long height) {
		running = true;
		startHeight = height;
	}

	/* The height the ledger was at when the sync began */
	long end() {
		running = false;
		return startHeight;
	}

	private Stream<Claim> standing(long now) {
		return claims.values().stream().filter(claim -> !lapsed(claim, now));
	}

	/* A clock that was set back makes nothing lapse */
	private boolean lapsed(Claim claim, long now) {
		return now - claim.heardAt() >= lapseMillis;
	}
}
