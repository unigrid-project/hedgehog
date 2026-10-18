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

package org.unigrid.hedgehog.service.storage;

import io.netty.channel.Channel;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.Correlated;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;

/* Topology never lists the node itself, so the local gridnode is answered by its own keeper in-process */
@RequiredArgsConstructor
public class NettyFragmentTransport implements FragmentTransport {
	/* Runs on the calling thread; going through supplyAsync only turns a keeper failure into a failed future */
	private static final Executor IN_PLACE = Runnable::run;

	private final Supplier<Set<Node>> nodes;
	private final PendingRequests pending;
	private final Supplier<Optional<String>> selfId;
	private final Supplier<FragmentKeeper> localKeeper;

	@Override
	public CompletableFuture<StorageAck> store(final Gridnode target, final byte[] fragment) {
		final StoreFragment request = StoreFragment.builder().requestId(pending.nextRequestId()).fragment(fragment)
			.build();

		return route(target, request, StorageAck.class, keeper -> StorageAck.builder()
			.requestId(request.getRequestId()).status(keeper.store(fragment)).build());
	}

	@Override
	public CompletableFuture<FragmentReply> fetch(final Gridnode target, final GroupId groupId) {
		final FetchFragment request = FetchFragment.builder().requestId(pending.nextRequestId()).groupId(groupId)
			.build();

		return route(target, request, FragmentReply.class,
			keeper -> fetched(request.getRequestId(), keeper.fetch(groupId)));
	}

	@Override
	public CompletableFuture<FragmentStatus> has(final Gridnode target, final List<GroupId> groupIds) {
		final HasFragment request = HasFragment.builder().requestId(pending.nextRequestId()).groupIds(groupIds)
			.build();

		return route(target, request, FragmentStatus.class, keeper -> FragmentStatus.builder()
			.requestId(request.getRequestId()).entries(keeper.census(groupIds)).build());
	}

	@Override
	public CompletableFuture<StorageAck> delete(final Gridnode target, final GroupId groupId, final byte[] publicKey,
		final long timestamp, final byte[] signature) {

		final DeleteGroup request = DeleteGroup.builder().requestId(pending.nextRequestId()).groupId(groupId)
			.publicKey(publicKey).timestamp(timestamp).signature(signature).build();

		return route(target, request, StorageAck.class, keeper -> StorageAck.builder()
			.requestId(request.getRequestId())
			.status(keeper.delete(groupId, publicKey, timestamp, signature)).build());
	}

	private static FragmentReply fetched(final long requestId, final Optional<byte[]> fragment) {
		return FragmentReply.builder().requestId(requestId)
			.status(fragment.isPresent() ? StorageStatus.OK : StorageStatus.NOT_FOUND)
			.fragment(fragment.orElse(new byte[0])).build();
	}

	private <T extends Correlated> CompletableFuture<T> route(final Gridnode target, final Correlated request,
		final Class<T> type, final Function<FragmentKeeper, T> answer) {

		if (isSelf(target)) {
			return CompletableFuture.supplyAsync(() -> answer.apply(localKeeper.get()), IN_PLACE);
		}

		return send(target, request, type);
	}

	private boolean isSelf(final Gridnode target) {
		return selfId.get().filter(id -> id.equals(target.getId())).isPresent();
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
				pending.fail(request.getRequestId(), written.cause());
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
		} catch (URISyntaxException | UnknownHostException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/* Node.equals compares resolved addresses and throws on a node without one */
	private static boolean isResolved(final Node node) {
		return node.getAddress() != null && !node.getAddress().isUnresolved();
	}
}
