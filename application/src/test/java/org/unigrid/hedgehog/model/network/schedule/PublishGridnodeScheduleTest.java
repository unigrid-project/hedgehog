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

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.bitcoinj.core.ECKey;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.server.TestServer;

public class PublishGridnodeScheduleTest extends BaseScheduleTest<PublishGridnodeSchedule, PublishGridnode, Void> {
	public static final int PERIOD_MS = 250;
	public static final int WAIT_TIME_MS = 3000;
	public static final double TOLERANCE = 0.3; /* 30% */

	@Inject
	private Topology topology;


	public PublishGridnodeScheduleTest() {
		super(PERIOD_MS, TimeUnit.MILLISECONDS, PublishGridnodeSchedule.class);
	}

	public List<ECKey> provideKeys(int num) {
		List<ECKey> keys = new ArrayList<>();
		for (int i = 0; i < num; i++) {
			keys.add(new ECKey());
		}
		return keys;
	}
	
	private enum Family {
		IP4, IP6
	}

	@SneakyThrows
	public void provideActiveGridnode(@ForAll Family family,
		@ForAll @IntRange(min = 1024, max = 65535) int port, String key) {

		String address = switch (family) {
			case IP4 -> ArbitraryGenerator.ip4();
			case IP6 -> ArbitraryGenerator.ip6();
		};

		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress((family == Family.IP4 ? "%s" : "[%s]").formatted(address));
		} else {
			node = Node.fromAddress((family == Family.IP4 ? "%s:%d" : "[%s]:%d").formatted(address, port));
		}
		topology.addNode(node);
		Gridnode gridnode = Gridnode.builder().hostName(node.getAddress().getHostName()).id(key)
			.status(Gridnode.Status.ACTIVE).build();
		topology.addGridnode(gridnode);
	}

	@Property(tries = 3)
	public void shoulBeAbleToPropagateNetwork(@ForAll("provideTestServers") List<TestServer> servers) throws Exception {
		final AtomicInteger invocations = new AtomicInteger();
		final List<Connection> connections = new ArrayList<>();
		List<ECKey> keys = provideKeys(8);
		
		for (ECKey key : keys) {
			provideActiveGridnode(Family.IP4, new Random().nextInt(1024, 65535), key.getPublicKeyAsHex());
		}

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