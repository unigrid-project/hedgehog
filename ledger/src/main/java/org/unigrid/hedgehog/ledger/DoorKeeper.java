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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.Door.Snapshot;

/* The side of the door that belongs to the serial executor. After a turn it publishes what the door reads if any of it
   changed: the height of the ledger, the validators, the peers that are penalised and the peers that are connected.
   The connected peers are asked for again only when the driver says they may have changed, as listing them is work.
   A peer that has become penalised is also cut off once for each penalty, by asking the network to close its
   connections. Used on the serial executor only. */
@Slf4j
final class DoorKeeper {
	private final Door door;
	private final PeerLimiter limiter;
	private final PeerView network;
	private final Set<String> cut = new HashSet<>();
	private ValidatorSet validators;
	private long epoch = -1;
	private boolean connectedChanged = true;

	DoorKeeper(Door door, PeerLimiter limiter, PeerView network) {
		this.door = door;
		this.limiter = limiter;
		this.network = network;
	}

	void connectedMayHaveChanged() {
		connectedChanged = true;
	}

	void afterTurn(Ledger ledger) {
		if (ledger != null && changed(ledger)) {
			publish(ledger);
		}
	}

	private boolean changed(Ledger ledger) {
		return ledger.height() != door.snapshot().height() || ledger.validators() != validators
			|| limiter.penaltyEpoch() != epoch || connectedChanged;
	}

	private void publish(Ledger ledger) {
		final Snapshot before = door.snapshot();
		final ValidatorSet now = ledger.validators();
		final long penalties = limiter.penaltyEpoch();
		final Set<String> penalised = penalties == epoch ? before.penalised() : limiter.penalisedIds();

		door.publish(new Snapshot(ledger.height(), now == validators ? before.validators() : Set.copyOf(now.keys()),
			penalised, connectedChanged ? network.ids() : before.known()));
		validators = now;
		connectedChanged = false;

		if (penalties != epoch) {
			epoch = penalties;
			cutOff(penalised);
		}
	}

	private void cutOff(Set<String> penalised) {
		cut.retainAll(penalised);

		final List<String> fresh = penalised.stream().filter(cut::add).toList();

		if (!fresh.isEmpty()) {
			final Set<String> present = network.ids();

			fresh.stream().filter(present::contains).forEach(this::disconnect);
		}
	}

	private void disconnect(String id) {
		try {
			network.disconnect(id);
		} catch (RuntimeException e) {
			log.atDebug().setCause(e).log("Could not cut off {}", id);
		}
	}
}
