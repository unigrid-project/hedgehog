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

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/* The peers of the network as the driver sees them during one run of the serial executor. Listing them is work, and
   the driver needs them for every item it passes on and every claim it looks at, so they are asked for once in a run
   and the same list is used until the run is over. A peer that connects in the middle of a run is seen by the next
   one. Used on the serial executor only. */
final class PeerView implements LedgerNetwork {
	private final LedgerNetwork network;
	private List<LedgerPeer> peers;
	private Set<String> ids;

	PeerView(LedgerNetwork network) {
		this.network = network;
	}

	/* What the next run asks the network for again */
	void nextRun() {
		peers = null;
		ids = null;
	}

	@Override
	public List<LedgerPeer> peers() {
		if (peers == null) {
			peers = network.peers();
		}

		return peers;
	}

	/* The ids of the peers that are connected; one that cannot say who it is counts for nothing */
	Set<String> ids() {
		if (ids == null) {
			ids = peers().stream().map(PeerView::idOf).filter(Objects::nonNull).collect(Collectors.toSet());
		}

		return ids;
	}

	@Override
	public void disconnect(String peerId) {
		network.disconnect(peerId);
	}

	static String idOf(LedgerPeer peer) {
		try {
			return peer.id();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
