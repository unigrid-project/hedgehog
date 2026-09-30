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

package org.unigrid.hedgehog.model.network.handler;

import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collector;
import lombok.SneakyThrows;
import mockit.Mocked;
import mockit.Mock;
import mockit.MockUp;
import mockit.Tested;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import static org.awaitility.Awaitility.await;
import org.bitcoinj.core.ECKey;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.command.option.GridnodeOptions;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.model.network.schedule.PingSchedule;
import org.unigrid.hedgehog.model.network.schedule.PublishGridnodeSchedule;
import org.unigrid.hedgehog.server.TestServer;

public class PublishGridnodeTest extends BaseHandlerTest<PublishGridnode, PublishGridnodeChannelHandler> {

	/*public final class FakeGridnodeOptions extends MockUp<GridnodeOptions> {

		private String gridnodeKey;

		@Mock
		public String getGridnodeKey() {
			System.out.println("Getting gridnode key");
			ECKey key = new ECKey();
			return key.getPublicKeyAsHex();
		}
	}*/
	
	@Inject
	private Topology topology;

	public PublishGridnodeTest() {
		super(PublishGridnodeChannelHandler.class);
	}

	@Property(tries = 30, shrinking = ShrinkingMode.OFF)
	public void shoulPropagateGridnodeToNetwork(@ForAll("provideTestServers") List<TestServer> servers,
		@Mocked PublishGridnodeSchedule schedule) throws Exception {
		final AtomicInteger invocations = new AtomicInteger();
		int expectedInvocations = 0;

		setChannelCallback(Optional.of((ctx, publishGridnode) -> {
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
			connection.send(PublishGridnode.builder().gridnode(gridnode).build());
			expectedInvocations++;

			await().untilAtomic(invocations, is(greaterThanOrEqualTo(expectedInvocations)));
			connection.closeDirty();
		}

		await().untilAtomic(invocations, is(greaterThanOrEqualTo(expectedInvocations)));
	}
}
