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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;
import org.unigrid.hedgehog.server.TestServer;

public class PublishPeersScheduleTest extends BaseScheduleTest<PublishPeersSchedule, PublishPeers, Void> {
	public static final int PERIOD_MS = 250;
	public static final int WAIT_TIME_MS = 3000;
	public static final double TOLERANCE = 0.3; /* 30% */

	public PublishPeersScheduleTest() {
		super(PERIOD_MS, TimeUnit.MILLISECONDS, PublishPeersSchedule.class);
	}

	@Property(tries = 3)
	public void shoulBeAbleToPropagateNetwork(@ForAll("provideTestServers") List<TestServer> servers) throws Exception {
		final AtomicInteger invocations = new AtomicInteger();
		final List<Connection> connections = new ArrayList<>();

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();

			connections.add(new P2PClient(host, port));
		}

		setScheduleCallback(Optional.of(channel -> {
			/* Only count scheduling in one directon */
			if (RegisterQuicChannelInitializer.Type.CLIENT.is(channel)) {
				invocations.incrementAndGet();
			}
		}));

		Thread.sleep(WAIT_TIME_MS);
		setScheduleCallback(Optional.empty());

		for (Connection connection : connections) {
			connection.close();
		}

		final double expectedInvocations = Math.round((float) WAIT_TIME_MS / PERIOD_MS) * servers.size();

		double toleranceAmount = expectedInvocations * TOLERANCE;
		toleranceAmount = toleranceAmount < 1 ? 1 : toleranceAmount;

		assertThat(invocations.doubleValue(), is(closeTo(expectedInvocations, toleranceAmount)));
	}
}
