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

package org.unigrid.hedgehog.server.p2p;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.jboss.weld.environment.se.WeldContainer;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.ConnectionHandler;
import org.unigrid.hedgehog.server.BaseServerTest;

/* A peer drops a connection only once its channel is inactive, and a QUIC connection that no one closes stays active
   until it idles out, which takes minutes: a node that stops has to close its connections, each way, for its peers
   to dial it again when it is back. Each example stops a server of its own. */
public class P2PServerStopTest extends BaseServerTest {
	private static final Duration AT_ONCE = Duration.ofSeconds(5);

	@Example
	@SneakyThrows
	public void closesTheConnectionsThatPeersDialed() {
		final P2PServer stopping = servers().get(0).getP2p();
		final P2PClient peer = new P2PClient(stopping.getHostName(), stopping.getPort());

		try {
			stopping.destroy();
			await().atMost(AT_ONCE).until(() -> peer.getChannel().parent().isActive(), is(false));
		} finally {
			peer.closeDirty();
		}
	}

	/* The server of the peer sees the connection that the stopping node dialed go inactive */
	@Example
	@SneakyThrows
	public void closesTheConnectionsThatItDialed() {
		final Set<Channel> closedOnThePeer = ConcurrentHashMap.newKeySet();

		new MockUp<ConnectionHandler>() {
			@Mock
			void channelInactive(Invocation invocation, ChannelHandlerContext context) {
				closedOnThePeer.add(context.channel());
				invocation.proceed();
			}
		};

		final P2PServer stopping = servers().get(1).getP2p();
		final P2PServer peer = servers().get(2).getP2p();
		final Topology topology = WeldContainer.instance(containerName(1)).select(Topology.class).get();
		final Node node = Node.fromAddress(peer.getHostName() + ":" + peer.getPort());
		final P2PClient dialed = new P2PClient(peer.getHostName(), peer.getPort());

		topology.addNode(node);
		topology.modifyNode(node, held -> held.setConnection(Optional.of(dialed)));
		stopping.destroy();
		await().atMost(AT_ONCE).until(() -> closedOnThePeer, is(not(empty())));
	}
}
