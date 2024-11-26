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
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.packet.Correlated;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.packet.FetchFragment;
import org.unigrid.hedgehog.model.network.packet.FragmentReply;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
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
	private static final GroupId GROUP = GroupId.of(new byte[GroupId.SIZE]);

	@BeforeProperty
	private void mockKeeper() {
		new MockUp<FragmentKeeper>() {
			@Mock public StorageStatus store(byte[] encoded) {
				return StorageStatus.QUOTA;
			}

			@Mock public Optional<byte[]> fetch(GroupId groupId) {
				return Optional.of(new byte[] { 7 });
			}

			@Mock public List<FragmentStatus.Entry> census(List<GroupId> groupIds) {
				return List.of(new FragmentStatus.Entry(groupIds.get(0), FragmentStatus.State.HELD, 3));
			}

			@Mock public StorageStatus delete(GroupId groupId, byte[] publicKey, long timestamp, byte[] signature) {
				return StorageStatus.INVALID;
			}
		};
	}

	@SneakyThrows
	private <T extends Correlated> T request(P2PClient client, LongFunction<Packet> request, Class<T> type) {
		/* Every test server runs in its own container, so an injected field could belong to another one than
		   the container the reply handler resolves from */
		final PendingRequests pendingRequests = CDI.current().select(PendingRequests.class).get();
		final long id = pendingRequests.nextRequestId();
		final CompletableFuture<T> response = pendingRequests.register(id, client.getChannel(), type);

		client.send(request.apply(id));
		return response.get(10, TimeUnit.SECONDS);
	}

	@SneakyThrows
	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void answersEveryStorageRequest(@ForAll("provideTestServers") List<TestServer> servers) {
		for (TestServer server : servers) {
			final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());

			try {
				assertThat(request(client, id -> StoreFragment.builder().requestId(id).fragment(new byte[] { 1 })
					.build(), StorageAck.class).getStatus(), equalTo(StorageStatus.QUOTA));
				assertThat(request(client, id -> FetchFragment.builder().requestId(id).groupId(GROUP).build(),
					FragmentReply.class).getFragment(), equalTo(new byte[] { 7 }));
				assertThat(request(client, id -> HasFragment.builder().requestId(id).groupIds(List.of(GROUP)).build(),
					FragmentStatus.class).getEntries().get(0).getIndex(), equalTo(3));
				assertThat(request(client, id -> DeleteGroup.builder().requestId(id).groupId(GROUP)
					.publicKey(new byte[32]).timestamp(1).signature(new byte[64]).build(),
					StorageAck.class).getStatus(), equalTo(StorageStatus.INVALID));
			} finally {
				client.closeDirty();
			}
		}
	}
}
