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

package org.unigrid.hedgehog.service.ledger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.LedgerNetwork;
import org.unigrid.hedgehog.ledger.LedgerPeer;

/* The network of a driver, which also says what the driver sent to which peer, as that is what "not back to where it
   came from" is about and nothing on the receiving side can tell it */
@RequiredArgsConstructor
final class RecordingNetwork implements LedgerNetwork {
	record Sent(String to, Object wire) {
	}

	private final LedgerNetwork network;
	private final List<Sent> sent = new CopyOnWriteArrayList<>();

	@Override
	public List<LedgerPeer> peers() {
		return network.peers().stream().<LedgerPeer>map(Recording::new).toList();
	}

	List<Sent> sent(final Class<?> type) {
		return sent.stream().filter(entry -> type.isInstance(entry.wire())).toList();
	}

	@RequiredArgsConstructor
	private final class Recording implements LedgerPeer {
		private final LedgerPeer peer;

		@Override
		public String id() {
			return peer.id();
		}

		@Override
		public void send(final Object wire) {
			sent.add(new Sent(peer.id(), wire));
			peer.send(wire);
		}

		@Override
		public CompletableFuture<BlockBatch> request(final BlockRequest request) {
			return peer.request(request);
		}
	}
}
