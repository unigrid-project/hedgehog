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

package org.unigrid.hedgehog.model.producer;

import io.netty.handler.codec.socksx.v5.Socks5AddressType;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.storage.placement.GridnodeDirectory;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.session.SocksAddress;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.proxy.socks.TunnelFailure;
import org.unigrid.hedgehog.proxy.socks.TunnelListener;
import org.unigrid.hedgehog.service.chain.ChainHost;
import org.unigrid.hedgehog.service.chain.ChainTransport;
import org.unigrid.hedgehog.service.chain.ProxySettingsSource;

public class TunnelProducerTest {
	private static final int WAIT_SECONDS = 10;
	private static final String SCHEDULER = "chain-client";
	private static final String JOINS = "chain-client-join";

	/* The tunnel of a node that is no gridnode and knows no gridnodes */
	private static Tunnel tunnel(final Optional<ProxySettings> settings) {
		final ProxySettingsSource source = () -> settings;
		final GridnodeDirectory directory = new TopologyGridnodeDirectory(() -> List.of(), () -> "client");
		final ChainTransport transport = peer -> Optional.empty();

		return new TunnelProducer().tunnel(new ChainHost(source, directory, transport), transport, directory, source,
			new PendingRequests());
	}

	/* What the tunnel tells about one stream, from whichever thread it tells it */
	private static List<String> heardOpening(final Tunnel tunnel) {
		final List<String> heard = new CopyOnWriteArrayList<>();

		tunnel.open(new SocksAddress(Socks5AddressType.DOMAIN, "example.org", 443), new TunnelListener() {
			@Override
			public void connected(SocksAddress bound) {
				heard.add("connected");
			}

			@Override
			public void failed(TunnelFailure failure) {
				heard.add(failure.name());
			}

			@Override
			public void received(byte[] bytes) {
				heard.add("received");
			}

			@Override
			public void writabilityChanged(boolean writable) {
				heard.add("writable");
			}

			@Override
			public void closed() {
				heard.add("closed");
			}
		});

		return heard;
	}

	private static Set<Thread> threadsNamed(final String name) {
		return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals(name))
			.collect(Collectors.toSet());
	}

	private static Set<Thread> startedSince(final Set<Thread> before, final String name) {
		final Set<Thread> started = new HashSet<>(threadsNamed(name));

		started.removeAll(before);
		return started;
	}

	@Example
	public void refusesEveryStreamAsNotAllowedByRulesetWhileTheSporkIsAbsent() {
		final Set<Thread> schedulers = threadsNamed(SCHEDULER);
		final Set<Thread> joins = threadsNamed(JOINS);

		assertThat(heardOpening(tunnel(Optional.empty())), equalTo(List.of("NOT_ALLOWED")));

		/* No session and no join was started for it, so the chain heard nothing */
		assertThat(startedSince(schedulers, SCHEDULER), empty());
		assertThat(startedSince(joins, JOINS), empty());
	}

	@Example
	public void triesToReachAnExitOnceTheSporkAllowsIt() {
		final List<String> heard = heardOpening(tunnel(Optional.of(ProxySettings.DEFAULTS)));

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !heard.isEmpty());
		assertThat(heard, equalTo(List.of("NETWORK_UNREACHABLE")));
	}

	@Example
	public void runsTheSessionsAndTheDeadlinesOfItsStreamsOnOneDaemonThread() {
		final Set<Thread> before = threadsNamed(SCHEDULER);
		final Tunnel tunnel = tunnel(Optional.of(ProxySettings.DEFAULTS));
		final List<List<String>> heard = List.of(heardOpening(tunnel), heardOpening(tunnel), heardOpening(tunnel));

		await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> heard.stream().noneMatch(List::isEmpty));

		final Set<Thread> started = startedSince(before, SCHEDULER);

		assertThat(started.size(), is(1));
		assertThat(started.iterator().next().isDaemon(), is(true));
	}

	@Example
	public void takesTheDeadlineOfAStreamThatEndedOutOfTheQueue() {
		final ScheduledThreadPoolExecutor scheduler = TunnelProducer.scheduler();
		final Thread thread = scheduler.getThreadFactory().newThread(() -> { });

		assertThat(scheduler.getRemoveOnCancelPolicy(), is(true));
		assertThat(scheduler.getCorePoolSize(), is(1));
		assertThat(thread.getName(), equalTo(SCHEDULER));
		assertThat(thread.isDaemon(), is(true));
		scheduler.shutdown();
	}
}
