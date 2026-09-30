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

package org.unigrid.hedgehog.model.network;

import jakarta.enterprise.inject.spi.CDI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.packet.Correlated;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus.Entry;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus.State;
import org.unigrid.hedgehog.model.network.packet.HasFragment;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.StorageStatus;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.storage.FragmentKeeper;

public class StoragePipelineTest extends BaseServerTest {
	private static final GroupId HELD_GROUP = groupOf(0);
	private static final GroupId MISSING_GROUP = groupOf(1);
	private static final byte[] FRAGMENT = new byte[] { 7 };
	private static final int HELD_INDEX = 3;

	private static GroupId groupOf(final int first) {
		final byte[] id = new byte[GroupId.SIZE];

		id[0] = (byte) first;
		return GroupId.of(id);
	}

	@BeforeProperty
	private void mockKeeper() {
		new MockUp<FragmentKeeper>() {
			@Mock public StorageStatus store(byte[] encoded) {
				return StorageStatus.QUOTA;
			}

			@Mock public Optional<byte[]> fetch(GroupId groupId) {
				return HELD_GROUP.equals(groupId) ? Optional.of(FRAGMENT) : Optional.empty();
			}

			@Mock public List<Entry> census(List<GroupId> groupIds) {
				return groupIds.stream().map(id -> HELD_GROUP.equals(id) ? new Entry(id, State.HELD, HELD_INDEX)
					: new Entry(id, State.NONE, 0)).collect(Collectors.toList());
			}

			@Mock public StorageStatus delete(GroupId groupId, byte[] publicKey, long timestamp, byte[] signature) {
				return StorageStatus.INVALID;
			}
		};
	}

	@SneakyThrows
	private <T extends Correlated> T request(final P2PClient client, final LongFunction<Packet> request,
		final Class<T> type) {

		/* Every test server runs in its own container, so an injected field could belong to another one than
		   the container the reply handler resolves from */
		final PendingRequests pendingRequests = CDI.current().select(PendingRequests.class).get();
		final long id = pendingRequests.nextRequestId();
		final CompletableFuture<T> response = pendingRequests.register(id, client.getChannel(), type);

		client.send(request.apply(id));
		return response.get(10, TimeUnit.SECONDS);
	}

	private FragmentReply fetch(final P2PClient client, final GroupId groupId) {
		return request(client, id -> FetchFragment.builder().requestId(id).groupId(groupId).build(),
			FragmentReply.class);
	}

	private void assertAnswers(final P2PClient client) {
		assertThat(request(client, id -> StoreFragment.builder().requestId(id).fragment(new byte[] { 1 }).build(),
			StorageAck.class).getStatus(), equalTo(StorageStatus.QUOTA));

		final FragmentReply held = fetch(client, HELD_GROUP);
		final FragmentReply missing = fetch(client, MISSING_GROUP);

		assertThat(held.getStatus(), equalTo(StorageStatus.OK));
		assertThat(held.getFragment(), equalTo(FRAGMENT));
		assertThat(missing.getStatus(), equalTo(StorageStatus.NOT_FOUND));
		assertThat(missing.getFragment(), equalTo(new byte[0]));

		assertThat(request(client, id -> HasFragment.builder().requestId(id)
			.groupIds(List.of(HELD_GROUP, MISSING_GROUP)).build(), FragmentStatus.class).getEntries(),
			contains(new Entry(HELD_GROUP, State.HELD, HELD_INDEX), new Entry(MISSING_GROUP, State.NONE, 0)));

		assertThat(request(client, id -> DeleteGroup.builder().requestId(id).groupId(HELD_GROUP)
			.publicKey(new byte[32]).timestamp(1).signature(new byte[64]).build(),
			StorageAck.class).getStatus(), equalTo(StorageStatus.INVALID));
	}

	@SneakyThrows
	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void answersEveryStorageRequest(@ForAll("provideTestServers") List<TestServer> servers) {
		for (TestServer server : servers) {
			final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());

			try {
				assertAnswers(client);
			} finally {
				client.closeDirty();
			}
		}
	}
}
