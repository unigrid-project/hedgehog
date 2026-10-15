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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/* Peers that remember everything they are sent and the threads it was sent from, and answer no request */
final class RecordingNetwork implements LedgerNetwork {
	static final class RecordingPeer implements LedgerPeer {
		private final String id;
		private final List<Object> sent = new CopyOnWriteArrayList<>();
		private final Set<String> threads = ConcurrentHashMap.newKeySet();
		private volatile boolean failing;

		private RecordingPeer(String id) {
			this.id = id;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public void send(Object wire) {
			threads.add(Thread.currentThread().getName());

			if (failing) {
				throw new IllegalStateException("The connection to " + id + " is gone");
			}

			sent.add(wire);
		}

		@Override
		public CompletableFuture<BlockBatch> request(BlockRequest request) {
			return new CompletableFuture<>();
		}

		<T> List<T> sent(Class<T> type) {
			return sent.stream().filter(type::isInstance).map(type::cast).toList();
		}

		List<Object> sent() {
			return List.copyOf(sent);
		}

		Set<String> threads() {
			return threads;
		}

		void fail() {
			failing = true;
		}
	}

	private final List<LedgerPeer> peers = new CopyOnWriteArrayList<>();
	private final Set<String> threads = ConcurrentHashMap.newKeySet();
	private volatile RuntimeException refusal;

	/* From now on the list of peers cannot be had, as when the network under it has failed */
	void refuse(RuntimeException reason) {
		refusal = reason;
	}

	RecordingPeer connect(String id) {
		final RecordingPeer peer = new RecordingPeer(id);

		add(peer);
		return peer;
	}

	/* A peer of another kind, such as one that hands what it is sent to another driver */
	void add(LedgerPeer peer) {
		peers.add(peer);
	}

	/* The peer has left; asking whether it is here does not count as a use of the list */
	void remove(String id) {
		peers.removeIf(peer -> peer.id().equals(id));
	}

	boolean has(String id) {
		return peers.stream().anyMatch(peer -> peer.id().equals(id));
	}

	Set<String> threads() {
		return threads;
	}

	@Override
	public List<LedgerPeer> peers() {
		threads.add(Thread.currentThread().getName());

		if (refusal != null) {
			throw refusal;
		}

		return List.copyOf(peers);
	}
}
