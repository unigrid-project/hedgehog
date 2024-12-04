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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.StorageAck;
import org.unigrid.hedgehog.model.network.packet.StoreFragment;
import org.unigrid.hedgehog.model.storage.StorageStatus;

public class NettyFragmentTransportTest {
	private static final Gridnode TARGET = Gridnode.builder().id("a").hostName("10.0.0.1:52883")
		.status(Gridnode.Status.ACTIVE).build();

	@SneakyThrows
	private static Node nodeWith(EmbeddedChannel channel) {
		final Node node = Node.fromAddress(TARGET.getHostName());

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

	@Property(tries = 30)
	public void matchesRepliesToTheirRequests(@ForAll @Size(max = 64) byte[] fragment, @ForAll StorageStatus status) {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final PendingRequests pending = new PendingRequests();
		final FragmentTransport transport = new NettyFragmentTransport(() -> Set.of(nodeWith(channel)), pending);
		final CompletableFuture<StorageAck> ack = transport.store(TARGET, fragment);
		final StoreFragment sent = channel.readOutbound();

		assertThat(sent.getFragment(), equalTo(fragment));
		pending.complete(channel, StorageAck.builder().requestId(sent.getRequestId()).status(status).build());
		assertThat(ack.join().getStatus(), equalTo(status));
	}

	@SneakyThrows
	@Property(tries = 10)
	public void failsForGridnodesWithoutAConnection(@ForAll boolean known) {
		final Set<Node> nodes = known ? Set.of(Node.fromAddress(TARGET.getHostName())) : Set.of();
		final FragmentTransport transport = new NettyFragmentTransport(() -> nodes, new PendingRequests());

		assertThat(transport.store(TARGET, new byte[1]).isCompletedExceptionally(), is(true));
	}
}
