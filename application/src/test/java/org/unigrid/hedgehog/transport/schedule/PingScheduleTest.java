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

package org.unigrid.hedgehog.transport.schedule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.transport.initializer.RegisterQuicChannelInitializer;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.TestServer;

public class PingScheduleTest extends BaseScheduleTest<PingSchedule, Ping, Void> {
	public static final int PERIOD_MS = 75;
	public static final int ROUNDS = 40;
	public static final Duration PATIENCE = Duration.ofSeconds(30);

	public PingScheduleTest() {
		super(PERIOD_MS, TimeUnit.MILLISECONDS, PingSchedule.class);
	}

	@Property(tries = 5)
	public void shoulBeAbleToSchedulePing(@ForAll("provideTestServers") List<TestServer> servers) throws Exception {
		final AtomicInteger invocations = new AtomicInteger();
		final AtomicBoolean counting = new AtomicBoolean();
		final List<P2PClient> clients = new ArrayList<>();

		setScheduleCallback(Optional.of(channel -> {
			/* A period of 75 ms leaves a peer a few hundred milliseconds to answer before its connection is closed,
			   which a busy machine misses; the closing is left to MissedPingsTest */
			channel.attr(Ping.MISSED_PINGS_KEY).set(0);

			/* Only count scheduling in one directon */
			if (counting.get() && RegisterQuicChannelInitializer.Type.CLIENT.is(channel)) {
				invocations.incrementAndGet();
			}
		}));

		try {
			for (TestServer server : servers) {
				final String host = server.getP2p().getHostName();
				final int port = server.getP2p().getPort();

				clients.add(new P2PClient(host, port));
			}

			final long start = System.nanoTime();
			counting.set(true);

			await().atMost(PATIENCE).failFast("A connection closed while the pings were counted",
				() -> clients.stream().anyMatch(client -> !client.getChannel().isActive()))
				.untilAtomic(invocations, greaterThanOrEqualTo(ROUNDS * servers.size()));

			final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

			/* Half, because the fixed-rate schedule can make up a run delayed by a busy machine right after the count
			   starts */
			assertThat(elapsedMillis, greaterThanOrEqualTo((long) ROUNDS / 2 * PERIOD_MS));
		} finally {
			close(clients);
		}
	}

	/* Together, as each client waits out the quiet period of its event loop, and without waiting on its stream, whose
	   shutdown fails once it died with its connection and would hide why the property failed */
	private void close(List<P2PClient> clients) {
		clients.forEach(P2PClient::closeDirty);
		clients.forEach(client -> client.getGroup().ifPresent(group -> group.terminationFuture().awaitUninterruptibly()));
		setScheduleCallback(Optional.empty());
	}
}
