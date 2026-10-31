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

import java.util.concurrent.CompletableFuture;

/* This node itself where the driver expects the peer that an item came from: what its own caller, its timers and its
   node give it. No one is held to account for such an item, and what is said of it goes to every peer. It is never
   a peer on the network, so nothing is sent to it or asked of it. */
final class LocalSource implements LedgerPeer {
	static final LedgerPeer LOCAL = new LocalSource();
	private static final String NOT_A_PEER = "This node is not a peer";

	private LocalSource() {
	}

	static boolean isLocal(LedgerPeer from) {
		return from == LOCAL;
	}

	@Override
	public String id() {
		throw new UnsupportedOperationException(NOT_A_PEER);
	}

	@Override
	public void send(Object wire) {
		throw new UnsupportedOperationException(NOT_A_PEER);
	}

	@Override
	public CompletableFuture<BlockBatch> request(BlockRequest request) {
		throw new UnsupportedOperationException(NOT_A_PEER);
	}
}
