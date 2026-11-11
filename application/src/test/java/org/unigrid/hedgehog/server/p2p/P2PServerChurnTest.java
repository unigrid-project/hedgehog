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
import io.netty.channel.group.ChannelGroup;
import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.jqwik.NettyLeaks;
import org.unigrid.hedgehog.jqwik.ThreadSet;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.ConnectionLimiter;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;
import org.unigrid.hedgehog.transport.schedule.PublishPeersSchedule;
import org.unigrid.hedgehog.transport.schedule.PublishSporkSchedule;

/* A peer connects, says hello, pings, exchanges peers and sporks, and goes, with a close or without one. A node that
   serves peers for as long as it runs holds nothing of one once it has noticed that the peer is gone: what it
   registered for the peer is let go of, however the peer went, and its connection with it, while the connection of a
   peer that stays is kept. */
public class P2PServerChurnTest extends BaseDefaultNodeTest {
	private static final int CYCLES = 16;
	private static final int CONCURRENT = 8;

	/* The connection of a peer is closed twenty seconds after its last stream is, and the topology lets go of what
	   it knew of the peer in its next round, which comes at most about a minute and a half later. A peer whose close
	   was lost is noticed by the pings it leaves unanswered, about a minute and a half before that. */
	private static final Duration SETTLED = Duration.ofMinutes(3);
	private static final Duration EXCHANGED = Duration.ofSeconds(30);
	private static final Duration POLL = Duration.ofSeconds(1);

	private static NettyLeaks leaks;

	@Inject private Topology topology;
	@Inject private P2PServer p2p;

	/* Before the container starts, as a buffer that the node allocated before the watch began is not followed */
	@BeforeContainer
	private static void watchTheBuffers() throws ReflectiveOperationException {
		leaks = NettyLeaks.watch();
	}

	@AfterContainer
	private static void stopWatchingTheBuffers() {
		leaks.close();
	}

	private record Held(int nodes, int channels, int limited, int connections) {
	}

	@Example
	public void shouldHoldNothingOfPeersThatCameAndWent() throws Exception {
		final int connected = held().connections();
		final P2PClient staying = new P2PClient(p2p.getHostName(), p2p.getPort());

		try {
			await().atMost(EXCHANGED).until(() -> held().connections() == connected + 1);
			churnAndSettle(held());
			assertThat(staying.getChannel().isActive(), is(true));
		} finally {
			staying.close();
		}
	}

	/* The baseline holds the connection of the peer that stays, so the node is back at it once every other one is
	   gone and that one is still there */
	private void churnAndSettle(final Held baseline) throws Exception {
		cycle(0);

		final ThreadSet threads = ThreadSet.capture();
		final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT);

		try {
			final List<Future<Void>> peers = IntStream.rangeClosed(1, CYCLES)
				.mapToObj(number -> pool.submit(churn(number))).toList();

			for (Future<Void> peer : peers) {
				peer.get(SETTLED.toSeconds(), TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			assertThat(pool.awaitTermination(EXCHANGED.toSeconds(), TimeUnit.SECONDS), is(true));
		}

		await().atMost(SETTLED).pollInterval(POLL).pollInSameThread()
			.untilAsserted(() -> assertThat(deviations(baseline, threads), is(empty())));
	}

	private Callable<Void> churn(final int number) {
		return () -> {
			cycle(number);
			return null;
		};
	}

	/* The peers go in turn with a close, which waits for the end of the stream and the thread of the client, and
	   without one, which does not */
	private void cycle(final int number) throws Exception {
		final P2PClient peer = new P2PClient(p2p.getHostName(), p2p.getPort());

		try {
			exchange(peer.getChannel());
		} finally {
			if (number % 2 == 0) {
				peer.close();
			} else {
				peer.closeDirty();
			}
		}
	}

	/* Every client here comes from the host and with the port of the node, so the hello that it says as it connects
	   names the node itself, which the topology refuses, and a connection names no node after its first hello: the
	   peers here leave no node behind, and what the topology holds is compared all the same. The answer of the ping
	   is awaited, as the node has taken in what came before it by then. */
	private void exchange(final Channel stream) throws Exception {
		final Ping ping = Ping.builder().build();

		stream.attr(Ping.PING_TIME_KEY).set(ping.getNanoTime());
		stream.writeAndFlush(ping).sync();

		stream.eventLoop().submit(() -> {
			new PublishPeersSchedule().getConsumer().accept(stream);
			new PublishSporkSchedule().getConsumer().accept(stream);
		}).sync();

		await().atMost(EXCHANGED).until(() -> Objects.isNull(stream.attr(Ping.PING_TIME_KEY).get()));
	}

	private Held held() throws ReflectiveOperationException {
		final P2PServer server = CDIUtil.unproxy(p2p);
		final ChannelGroup connections = (ChannelGroup) field(P2PServer.class, "connections").get(server);
		final ConnectionLimiter limiter = (ConnectionLimiter) field(P2PServer.class, "limiter").get(server);
		final Map<?, ?> counts = (Map<?, ?>) field(ConnectionLimiter.class, "held").get(limiter);
		final int limited;

		synchronized (limiter) {
			limited = counts.values().stream().map(Integer.class::cast).mapToInt(Integer::intValue).sum();
		}

		return new Held(topology.cloneNodes().size(), topology.getChannels().size(), limited, connections.size());
	}

	private static Field field(final Class<?> owner, final String name) throws ReflectiveOperationException {
		final Field field = owner.getDeclaredField(name);

		field.setAccessible(true);
		return field;
	}

	private List<String> deviations(final Held baseline, final ThreadSet threads) throws Exception {
		final Held now = held();
		final List<String> found = new ArrayList<>();

		differ(found, "nodes in the topology", baseline.nodes(), now.nodes());
		differ(found, "channels in the channel map", baseline.channels(), now.channels());
		differ(found, "connections held by the limiter", baseline.limited(), now.limited());
		differ(found, "connections of the P2P server", baseline.connections(), now.connections());
		threads.started().forEach(name -> found.add("thread " + name + " started since the baseline"));
		leaks.collect().forEach(leak -> found.add("pooled buffer leaked: " + leak));
		return found;
	}

	private static void differ(final List<String> found, final String registry, final int baseline, final int now) {
		if (baseline != now) {
			found.add(registry + ": " + now + ", baseline " + baseline);
		}
	}
}
