/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

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

import io.netty.channel.Channel;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
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

@RequiredArgsConstructor
public class NettyFragmentTransport implements FragmentTransport {
	private final Supplier<Set<Node>> nodes;
	private final PendingRequests pending;

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

		final Optional<Channel> channel = channelOf(target);

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

	private Optional<Channel> channelOf(final Gridnode target) {
		if (target.getHostName() == null) {
			return Optional.empty();
		}

		try {
			final Node wanted = Node.fromAddress(target.getHostName());

			return Optional.of(wanted).filter(NettyFragmentTransport::isResolved)
				.flatMap(node -> nodes.get().stream().filter(NettyFragmentTransport::isResolved)
					.filter(node::equals).findFirst())
				.flatMap(Node::getConnection).map(Connection::getChannel);
		} catch (URISyntaxException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/* Node.equals compares resolved addresses and throws on a node without one */
	private static boolean isResolved(final Node node) {
		return node.getAddress() != null && !node.getAddress().isUnresolved();
	}
}
