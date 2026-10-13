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
package org.unigrid.hedgehog.model.network.handler;

import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.IntRange;
import static org.awaitility.Awaitility.await;
import org.bitcoinj.core.ECKey;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishAllGridnodes;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.schedule.PublishAllGridnodesSchedule;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.server.rest.GridnodeResourceTest;

public class PublishAllGridnodesTest extends BaseHandlerTest<PublishAllGridnodes, PublishAllGridnodesHandler>{

	@Inject
	private Topology topology;	
	
	public PublishAllGridnodesTest() {
		super(PublishAllGridnodesHandler.class);
	}
	
	@Property(tries = 30, shrinking = ShrinkingMode.OFF)
	public void shoulPropagateGridnodeToNetwork(@ForAll("provideTestServers") List<TestServer> servers,
		@Mocked PublishAllGridnodesSchedule schedule) throws Exception {
		final AtomicInteger invocations = new AtomicInteger();
		int expectedInvocations = 0;

		setChannelCallback(Optional.of((ctx, publishAllGridnodes) -> {
			/* Only count triggers on the server-side  */
			System.out.println("callback");
			if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
				invocations.incrementAndGet();
			}
		}));

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final Connection connection = new P2PClient(host, port);
			final ECKey key = new ECKey();
			Gridnode gridnode = Gridnode.builder().hostName(host + ":" + port).id(key.getPublicKeyAsHex())
				.build();
			topology.addNode(Node.builder().address(new InetSocketAddress(host, port))
				.build());
			topology.addGridnode(gridnode);
			connection.send(PublishAllGridnodes.builder().gridnodes(topology.cloneGridnode()).build());
			expectedInvocations++;

			await().untilAtomic(invocations, is(greaterThanOrEqualTo(expectedInvocations)));
			connection.closeDirty();
		}

		await().untilAtomic(invocations, is(greaterThanOrEqualTo(expectedInvocations)));
	}
}
