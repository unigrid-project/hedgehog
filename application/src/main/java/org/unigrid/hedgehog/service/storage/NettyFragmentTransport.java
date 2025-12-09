/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.service.storage;

import io.netty.channel.Channel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.GridnodeChannels;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.Correlated;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.GroupId;

public class NettyFragmentTransport implements FragmentTransport {
	private final GridnodeChannels channels;
	private final PendingRequests pending;

	public NettyFragmentTransport(final Supplier<Set<Node>> nodes, final PendingRequests pending) {
		this.channels = new GridnodeChannels(nodes);
		this.pending = pending;
	}

	@Override
	public CompletableFuture<StorageAck> store(final Gridnode target, final byte[] fragment) {
		return send(target, StoreFragment.builder().requestId(pending.nextRequestId()).fragment(fragment).build(),
			StorageAck.class);
	}

	@Override
	public CompletableFuture<FragmentReply> fetch(final Gridnode target, final GroupId groupId) {
		return send(target, FetchFragment.builder().requestId(pending.nextRequestId()).groupId(groupId).build(),
			FragmentReply.class);
	}

	@Override
	public CompletableFuture<FragmentStatus> has(final Gridnode target, final List<GroupId> groupIds) {
		return send(target, HasFragment.builder().requestId(pending.nextRequestId()).groupIds(groupIds).build(),
			FragmentStatus.class);
	}

	@Override
	public CompletableFuture<StorageAck> delete(final Gridnode target, final GroupId groupId, final byte[] publicKey,
		final long timestamp, final byte[] signature) {

		return send(target, DeleteGroup.builder().requestId(pending.nextRequestId()).groupId(groupId)
			.publicKey(publicKey).timestamp(timestamp).signature(signature).build(), StorageAck.class);
	}

	private <T extends Correlated> CompletableFuture<T> send(final Gridnode target, final Correlated request,
		final Class<T> type) {

		final Optional<Channel> channel = channels.of(target);

		if (channel.isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalStateException("No connection to the gridnode"));
		}

		final CompletableFuture<T> response = pending.register(request.getRequestId(), channel.get(), type);

		channel.get().writeAndFlush(request).addListener(written -> {
			if (!written.isSuccess()) {
				response.completeExceptionally(written.cause());
			}
		});

		return response;
	}
}
