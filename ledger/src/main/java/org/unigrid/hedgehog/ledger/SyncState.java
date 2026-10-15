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
   says proves nothing, as a status is not signed: it only tells whom to ask, and is dropped for a peer that is asked
   and cannot back it up, so that a peer which claims a chain it has not got holds nothing up for longer than one
   request. A claim that is not repeated lapses, so that a peer which left is not waited for. The most claims kept is
   limited, and a peer that speaks again takes the place of its last claim and goes to the back. Used by the driver
   on its serial executor only. */
final class SyncState {
	static final int MAX_CLAIMS = 256;

	record Claim(LedgerPeer peer, long height, long heardAt) {
	}

	private final long lapseMillis;
	private final Map<String, Claim> claims = new LinkedHashMap<>();
	private boolean heardAny;
	private boolean running;
	private long startHeight;

	SyncState(long lapseMillis) {
		this.lapseMillis = lapseMillis;
	}

	void heard(LedgerPeer peer, long height, long now) {
		final String id = peer.id();

		heardAny = true;
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

	/* Whether any peer has said anything since this node started: a node that has heard nobody waits for nobody */
	boolean heardAny() {
		return heardAny;
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
