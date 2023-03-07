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

package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.server.TestServer;

public class PublishPeersScheduleTest extends BaseScheduleTest<PublishPeersSchedule, PublishPeers, Void> {
	public static final int PERIOD_MS = 75;
	public static final int WAIT_TIME_MS = 1000;
	public static final double TOLERANCE = 0.05; /* 5% */

	public PublishPeersScheduleTest() {
		super(PERIOD_MS, TimeUnit.MILLISECONDS, PublishPeersSchedule.class);
	}

	/*@Provide
	public Arbitrary<List<Node>> provideNodes(@ForAll @ShortRange(min = 0, max = 10) short numNodes,
		@ForAll @Size(value = 60) byte[] signature, @ForAll Instant time, @ForAll Instant previousTime) {

		final List<Node> nodes = new ArrayList<>();

		for (int i = 0; i < numNodes; numNodes++) {
			final InetSocketAddress address = new InetSocketAddress(ArbitraryGenerator.ip(),
				Arbitraries.integers().between(0, 65535).sample()
			);

			nodes.add(Node.builder().address(address).channel(Optional.of(new EmbeddedChannel())).build());
		}
	}*/

	//@Property(tries = 5)
	public void shoulBeAbleToSchedulePublishingOfPeers(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll("provideNodes") List<Node> nodes) throws Exception {

		final AtomicInteger invocations = new AtomicInteger();
		final List<P2PClient> clients = new ArrayList<>();

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();

			clients.add(new P2PClient(host, port));
		}

		setScheduleCallback(Optional.of(channel -> {
			/* Only count scheduling in one directon */
			if (RegisterQuicChannelInitializer.Type.CLIENT.is(channel)) {
				invocations.incrementAndGet();
			}
		}));

		Thread.sleep(WAIT_TIME_MS);
		setScheduleCallback(Optional.empty());

		for (P2PClient client : clients) {
			client.close();
		}

		final double expectedInvocations = Math.round((float) WAIT_TIME_MS / PERIOD_MS) * servers.size();
		final double toleranceAmount = Math.ceil(expectedInvocations * TOLERANCE);
		assertThat(invocations.doubleValue(), is(closeTo(expectedInvocations, toleranceAmount)));
	}
}
