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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.storage.Fragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.model.storage.TestClock;
import org.unigrid.hedgehog.model.storage.crypto.GroupKey;
import org.unigrid.hedgehog.model.storage.store.FragmentStore;

public class NettyFragmentTransportTest {
	private static final Gridnode TARGET = Gridnode.builder().id("a").hostName("10.0.0.1:52883")
		.status(Gridnode.Status.ACTIVE).build();

	private static final Gridnode SELF = Gridnode.builder().id("self").hostName("10.0.0.2:52883")
		.status(Gridnode.Status.ACTIVE).build();

	private static final List<Function<FragmentTransport, CompletableFuture<?>>> OPERATIONS = List.of(
		transport -> transport.store(TARGET, new byte[1]),
		transport -> transport.fetch(TARGET, GroupId.of(new byte[GroupId.SIZE])),
		transport -> transport.has(TARGET, List.of()),
		transport -> transport.delete(TARGET, GroupId.of(new byte[GroupId.SIZE]), new byte[GroupKey.PUBLIC_KEY_SIZE],
			0, new byte[GroupKey.SIGNATURE_SIZE]));

	@SneakyThrows
	private static Node nodeWith(EmbeddedChannel channel, Gridnode gridnode) {
		final Node node = Node.fromAddress(gridnode.getHostName());

		node.setConnection(Optional.of(new Connection() {
			@Override public Channel getChannel() {
				return channel;
			}

			@Override public ChannelFuture send(Packet packet) {
				return channel.writeAndFlush(packet);
			}

			@Override public void close() {
				channel.close();
			}

			@Override public void closeDirty() {
				channel.close();
			}
		}));

		return node;
	}

	private static FragmentTransport remote(Set<Node> nodes, PendingRequests pending) {
		return new NettyFragmentTransport(() -> nodes, pending, () -> Optional.of(SELF.getId()), () -> {
			throw new IllegalStateException("A remote request must never reach the local keeper");
		});
	}

	private static void assertFetched(FragmentReply reply, Optional<byte[]> expected) {
		assertThat(reply.getStatus(), equalTo(expected.isPresent() ? StorageStatus.OK : StorageStatus.NOT_FOUND));
		assertThat(reply.getFragment(), equalTo(expected.orElse(new byte[0])));
	}

	@Provide
	Arbitrary<GroupId> groupIds() {
		return Arbitraries.bytes().array(byte[].class).ofSize(GroupId.SIZE).map(GroupId::of);
	}

	@Provide
	Arbitrary<byte[]> publicKeys() {
		return Arbitraries.bytes().array(byte[].class).ofSize(GroupKey.PUBLIC_KEY_SIZE);
	}

	@Provide
	Arbitrary<byte[]> signatures() {
		return Arbitraries.bytes().array(byte[].class).ofSize(GroupKey.SIGNATURE_SIZE);
	}

	@Property(tries = 30)
	public void matchesRepliesToTheirRequests(@ForAll @Size(max = 64) byte[] fragment, @ForAll StorageStatus status) {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final PendingRequests pending = new PendingRequests();
		final FragmentTransport transport = remote(Set.of(nodeWith(channel, TARGET)), pending);
		final CompletableFuture<StorageAck> ack = transport.store(TARGET, fragment);
		final StoreFragment sent = channel.readOutbound();

		assertThat(sent.getFragment(), equalTo(fragment));
		pending.complete(channel, StorageAck.builder().requestId(sent.getRequestId()).status(status).build());
		assertThat(ack.join().getStatus(), equalTo(status));
	}

	@Property(tries = 30)
	public void sendsFetchHasAndDeleteAsTheyWereAsked(@ForAll("groupIds") GroupId groupId,
		@ForAll @Size(max = 8) List<@From("groupIds") GroupId> groupIds, @ForAll("publicKeys") byte[] publicKey,
		@ForAll long timestamp, @ForAll("signatures") byte[] signature) {

		final EmbeddedChannel channel = new EmbeddedChannel();
		final PendingRequests pending = new PendingRequests();
		final FragmentTransport transport = remote(Set.of(nodeWith(channel, TARGET)), pending);

		final CompletableFuture<FragmentReply> fragment = transport.fetch(TARGET, groupId);
		final FetchFragment fetch = channel.readOutbound();
		final FragmentReply fragmentReply = FragmentReply.builder().requestId(fetch.getRequestId())
			.status(StorageStatus.OK).fragment(new byte[1]).build();

		assertThat(fetch.getGroupId(), equalTo(groupId));
		pending.complete(channel, fragmentReply);
		assertThat(fragment.join(), sameInstance(fragmentReply));

		final CompletableFuture<FragmentStatus> census = transport.has(TARGET, groupIds);
		final HasFragment has = channel.readOutbound();
		final FragmentStatus status = FragmentStatus.builder().requestId(has.getRequestId()).entries(List.of()).build();

		assertThat(has.getGroupIds(), equalTo(groupIds));
		pending.complete(channel, status);
		assertThat(census.join(), sameInstance(status));

		final CompletableFuture<StorageAck> deleted = transport.delete(TARGET, groupId, publicKey, timestamp, signature);
		final DeleteGroup delete = channel.readOutbound();
		final StorageAck ack = StorageAck.builder().requestId(delete.getRequestId()).status(StorageStatus.OK).build();

		assertThat(delete, equalTo(DeleteGroup.builder().requestId(delete.getRequestId()).groupId(groupId)
			.publicKey(publicKey).timestamp(timestamp).signature(signature).build()));
		pending.complete(channel, ack);
		assertThat(deleted.join(), sameInstance(ack));
	}

	@SneakyThrows
	@Property(tries = 10)
	public void failsForGridnodesWithoutAConnection(@ForAll boolean known) {
		final Set<Node> nodes = known ? Set.of(Node.fromAddress(TARGET.getHostName())) : Set.of();

		assertThat(remote(nodes, new PendingRequests()).store(TARGET, new byte[1]).isCompletedExceptionally(), is(true));
	}

	@Property(tries = 10)
	public void failsAtOnceWhenTheWriteFails(@ForAll @IntRange(min = 0, max = 3) int operation) {
		final EmbeddedChannel channel = new EmbeddedChannel();

		channel.close();

		final CompletableFuture<?> reply = OPERATIONS.get(operation)
			.apply(remote(Set.of(nodeWith(channel, TARGET)), new PendingRequests()));
		final CompletionException failure = assertThrows(CompletionException.class, () -> reply.getNow(null));

		assertThat(failure.getCause(), instanceOf(ClosedChannelException.class));
	}

	@SneakyThrows
	@Property(tries = 30)
	public void servesTheLocalGridnodeInProcess(@ForAll long seed,
		@ForAll @Size(min = 1, max = 16) List<@IntRange(min = 0, max = 3) Integer> operations) {

		final Random random = new Random(seed);
		final StorageSpork.SporkData parameters = StorageTestData.parameters();
		final TestClock clock = new TestClock();
		final FragmentKeeper local = new FragmentKeeper(new FragmentStore(Jimfs.newFileSystem(Configuration.unix())
			.getPath("/fragments"), clock), () -> Optional.of(parameters), () -> Optional.of(SELF.getId()));
		final FragmentKeeper twin = new FragmentKeeper(new FragmentStore(Jimfs.newFileSystem(Configuration.unix())
			.getPath("/fragments"), clock), () -> Optional.of(parameters), () -> Optional.of(SELF.getId()));
		final EmbeddedChannel channel = new EmbeddedChannel();
		final FragmentTransport transport = new NettyFragmentTransport(() -> Set.of(nodeWith(channel, SELF)),
			new PendingRequests(), () -> Optional.of(SELF.getId()), () -> local);
		final List<GroupKey> keys = List.of(StorageTestData.key(random), StorageTestData.key(random));

		for (int operation : operations) {
			final GroupKey key = keys.get(random.nextInt(keys.size()));
			final List<Fragment> group = StorageTestData.group(key, random);
			final byte[] fragment = group.get(random.nextInt(group.size())).encode();
			final long timestamp = random.nextLong();

			switch (operation) {
				case 0 -> assertThat(transport.store(SELF, fragment).join().getStatus(),
					equalTo(twin.store(fragment)));
				case 1 -> assertFetched(transport.fetch(SELF, key.groupId()).join(), twin.fetch(key.groupId()));
				case 2 -> assertThat(transport.has(SELF, List.of(key.groupId())).join().getEntries(),
					equalTo(twin.census(List.of(key.groupId()))));
				default -> assertThat(transport.delete(SELF, key.groupId(), key.publicKey(), timestamp,
					key.signDelete(timestamp)).join().getStatus(), equalTo(twin.delete(key.groupId(), key.publicKey(),
					timestamp, key.signDelete(timestamp))));
			}
		}

		assertThat(channel.outboundMessages().isEmpty(), is(true));
	}
}
