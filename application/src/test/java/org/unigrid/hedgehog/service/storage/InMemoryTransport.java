/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.service.storage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.Getter;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;

public class InMemoryTransport implements FragmentTransport {
	private final Map<String, FragmentKeeper> keepers = new ConcurrentHashMap<>();
	private final Set<String> offline = ConcurrentHashMap.newKeySet();
	private final Map<String, UnaryOperator<byte[]>> tampering = new ConcurrentHashMap<>();
	@Getter private final List<byte[]> sent = new CopyOnWriteArrayList<>();
	@Getter private final AtomicInteger fetches = new AtomicInteger();

	public void attach(String id, FragmentKeeper keeper) {
		keepers.put(id, keeper);
	}

	public void offline(String id) {
		offline.add(id);
	}

	public void online(String id) {
		offline.remove(id);
	}

	public boolean isOnline(String id) {
		return !offline.contains(id);
	}

	public void tamper(String id, UnaryOperator<byte[]> change) {
		tampering.put(id, change);
	}

	@Override
	public CompletableFuture<StorageAck> store(Gridnode target, byte[] fragment) {
		sent.add(fragment.clone());
		return call(target, keeper -> StorageAck.builder().status(keeper.store(fragment)).build());
	}

	@Override
	public CompletableFuture<FragmentReply> fetch(Gridnode target, GroupId groupId) {
		fetches.incrementAndGet();
		return call(target, keeper -> reply(target, keeper.fetch(groupId)));
	}

	@Override
	public CompletableFuture<FragmentStatus> has(Gridnode target, List<GroupId> groupIds) {
		return call(target, keeper -> FragmentStatus.builder().entries(keeper.census(groupIds)).build());
	}

	@Override
	public CompletableFuture<StorageAck> delete(Gridnode target, GroupId groupId, byte[] publicKey, long timestamp,
		byte[] signature) {

		return call(target, keeper -> StorageAck.builder()
			.status(keeper.delete(groupId, publicKey, timestamp, signature)).build());
	}

	private FragmentReply reply(Gridnode target, Optional<byte[]> fragment) {
		final UnaryOperator<byte[]> change = tampering.getOrDefault(target.getId(), UnaryOperator.identity());

		return fragment.map(bytes -> FragmentReply.builder().status(StorageStatus.OK).fragment(change.apply(bytes.clone()))
			.build()).orElseGet(() -> FragmentReply.builder().status(StorageStatus.NOT_FOUND).fragment(new byte[0]).build());
	}

	private <T> CompletableFuture<T> call(Gridnode target, Function<FragmentKeeper, T> action) {
		final FragmentKeeper keeper = keepers.get(target.getId());

		if (keeper == null || offline.contains(target.getId())) {
			return CompletableFuture.failedFuture(new IllegalStateException("Gridnode unreachable"));
		}

		return CompletableFuture.completedFuture(action.apply(keeper));
	}
}
