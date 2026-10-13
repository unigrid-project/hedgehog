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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.AskGridnodeDetails;
import org.unigrid.hedgehog.server.TestServer;
import static org.hamcrest.Matchers.*;
import static org.awaitility.Awaitility.await;
import org.bitcoinj.core.ECKey;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;

public class AskGridnodeDetailsChanelHandlerTest extends BaseHandlerTest<AskGridnodeDetails, AskGridnodeDetailsChannelHandler>{

	@Inject
	private Topology topology;

	public AskGridnodeDetailsChanelHandlerTest() {
		super(AskGridnodeDetailsChannelHandler.class);
	}

	@Property(tries = 30, shrinking = ShrinkingMode.OFF)
	public void shouldAskForGridnodes(@ForAll("provideTestServers") List<TestServer> servers) throws Exception{
		final AtomicInteger invocations = new AtomicInteger();
		int expectedInvocations = 0;

		setChannelCallback(Optional.of((ctx, publishAllGridnodes) -> {
			/* Only count triggers on the server-side  */
			System.out.println("callback");
			if (RegisterQuicChannelInitializer.Type.SERVER.is(ctx.channel())) {
				invocations.incrementAndGet();
			}
		}));
		
		for (TestServer server: servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final Connection connection = new P2PClient(host, port);
			final ECKey key = new ECKey();

			connection.send(AskGridnodeDetails.builder().message((short)1).build());
			expectedInvocations++;

			await().untilAtomic(invocations, is(greaterThanOrEqualTo(expectedInvocations)));
			connection.closeDirty();
		}

		await().untilAtomic(invocations, is(greaterThanOrEqualTo(expectedInvocations)));
	}
}
