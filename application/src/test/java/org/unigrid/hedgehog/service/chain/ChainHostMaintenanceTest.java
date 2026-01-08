/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Gap;

public class ChainHostMaintenanceTest {
	private static final long RETENTION_MILLIS = 30_000;
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final String DETAIL = "10.1.2.3:4567";
	private static final int THREADS = 8;
	private static final int ADDS = 2_000;
	private static final int TIMEOUT_SECONDS = 30;

	private record Reported(ChainId chainId, Gap gap) {
		/* One gap a listener was told of */
	}

	private static void receive(final ChainFleet fleet, final String member, final List<Entry> entries) {
		fleet.host(member).receive(fleet.stream(member, "gn-3"), ChainEntry.builder().entries(entries).build());
	}

	/* The entries of a feed that wait for the first one, which never arrives */
	private static List<Entry> behindAGap(final int writer, final ChainId chainId) {
		return ChainFleet.entries(ChainFleet.key(writer), chainId, 4, ChainFleet.NOW).subList(1, 4);
	}

	private static Gap gapOf(final int writer) {
		return new Gap(ChainFleet.writer(writer), 0, 3, Set.of(Fingerprint.NONE));
	}

	private static List<Reported> reportedTo(final ChainHost host) {
		final List<Reported> reported = new ArrayList<>();

		host.addGapListener((chain, gap) -> reported.add(new Reported(chain, gap)));
		return reported;
	}

	@Example
	public void agesEntriesOutOfAQuietChain() {
		final ChainFleet fleet = ChainFleet.group();
		final Chain chain = fleet.host("gn-0").chain(fleet.chain()).orElseThrow();

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), chain.id(), 3, ChainFleet.NOW));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);
		assertThat(chain.entries().size(), is(3));

		fleet.host("gn-0").maintain();

		assertThat(chain.entries(), is(empty()));
		assertThat(chain.heads().get(ChainFleet.writer(1)).seq(), is(2L));
	}

	@Example
	public void keepsTheEntriesUpToTheEdgeOfTheRetentionWindow() {
		final ChainFleet fleet = ChainFleet.group();
		final Chain chain = fleet.host("gn-0").chain(fleet.chain()).orElseThrow();

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), chain.id(), 3, ChainFleet.NOW));
		fleet.clock.addAndGet(RETENTION_MILLIS);
		fleet.host("gn-0").maintain();
		assertThat(chain.entries().size(), is(3));

		fleet.clock.addAndGet(1);
		fleet.host("gn-0").maintain();
		assertThat(chain.entries(), is(empty()));
	}

	@Example
	public void agesEntriesOutOfEveryChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");

		host.hostedChains().forEach(chainId -> receive(fleet, "gn-0",
			ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW)));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);

		host.maintain();

		host.hostedChains().forEach(chainId -> assertThat(host.chain(chainId).orElseThrow().entries(), is(empty())));
	}

	@Example
	public void servesNoFetchFromEntriesPastTheRetentionWindow() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final ChainId chainId = fleet.chain();
		final FetchEntries request = FetchEntries.builder().chainId(chainId)
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 2))).build();

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);
		assertThat(host.fetch(fleet.stream("gn-0", "gn-1"), request).size(), is(3));

		host.maintain();

		assertThat(host.fetch(fleet.stream("gn-0", "gn-1"), request), is(empty()));
	}

	@Example
	public void goesOnWithTheNextChainWhenOneChainFailsAndLogsOnlyTheClass() {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);
		final AtomicBoolean armed = new AtomicBoolean();
		final AtomicInteger reads = new AtomicInteger();
		final ChainHost host = new ChainHost(fleet.settings::get,
			new TopologyGridnodeDirectory(() -> fleet.gridnodes, () -> "gn-0"), peer -> Optional.empty(), () -> {
				if (armed.get() && reads.getAndIncrement() == 0) {
					throw new IllegalStateException(DETAIL);
				}

				return fleet.clock.get();
			}, fleet.random, new ManualScheduler());

		host.refresh();

		for (int index = 0; index < host.hostedChains().size(); index++) {
			final ChainId chainId = host.hostedChains().get(index);
			final EmbeddedChannel client = new EmbeddedChannel();

			host.join(client, JoinChain.builder().requestId(index).writer(ChainFleet.writer(index))
				.chainId(chainId).build());
			host.receive(client, ChainEntry.builder()
				.entries(ChainFleet.entries(ChainFleet.key(index), chainId, 3, ChainFleet.NOW)).build());
		}

		fleet.clock.addAndGet(RETENTION_MILLIS + 1);
		armed.set(true);

		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(host::maintain);

		assertThat(host.chain(host.hostedChains().get(0)).orElseThrow().entries().size(), is(3));
		host.hostedChains().stream().skip(1)
			.forEach(chainId -> assertThat(host.chain(chainId).orElseThrow().entries(), is(empty())));
		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	public void servesAMemberTheEntriesOfARetiredChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final ChainId chainId = fleet.chain();
		final FetchEntries request = FetchEntries.builder().chainId(chainId)
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 2))).build();

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW));
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();
		fleet.clock.set(ChainFleet.NOW);

		assertThat(host.retiredChains().contains(chainId), is(true));
		assertThat(host.fetch(fleet.stream("gn-0", "gn-1"), request).size(), is(3));
	}

	@Example
	public void tellsNoMemberWhereARetiredChainStands() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW));
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();
		fleet.clock.set(ChainFleet.NOW);
		List.of("gn-1", "gn-2", "gn-3").forEach(peer -> fleet.stream("gn-0", peer).releaseOutbound());

		host.reconcileRound();

		List.of("gn-1", "gn-2", "gn-3").forEach(peer -> assertThat(fleet.stream("gn-0", peer).outboundMessages(),
			is(empty())));
	}

	@Example
	public void trimsBeforeItTellsAMemberWhereItsChainsStand() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final Chain chain = host.chain(fleet.chain()).orElseThrow();
		final List<Integer> heldWhenTold = new ArrayList<>();

		for (final String peer : List.of("gn-1", "gn-2", "gn-3")) {
			fleet.stream("gn-0", peer).pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
				@Override
				public void write(final ChannelHandlerContext context, final Object packet,
					final ChannelPromise promise) {

					if (packet instanceof ChainHeads) {
						heldWhenTold.add(chain.entries().size());
					}

					context.write(packet, promise);
				}
			});
		}

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), chain.id(), 3, ChainFleet.NOW));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);

		host.tick();

		assertThat(heldWhenTold, contains(0));
	}

	@Example
	public void reportsTheGapsThatExpireToEveryListener() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final ChainId chainId = host.hostedChains().get(1);
		final List<Reported> first = new ArrayList<>();
		final List<Reported> second = new ArrayList<>();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 4, ChainFleet.NOW);

		host.addGapListener((chain, gap) -> first.add(new Reported(chain, gap)));
		host.addGapListener((chain, gap) -> {
			throw new IllegalStateException("A listener that fails");
		});
		host.addGapListener((chain, gap) -> second.add(new Reported(chain, gap)));

		receive(fleet, "gn-0", entries.subList(1, 4));
		host.maintain();
		assertThat(first, is(empty()));

		fleet.clock.addAndGet(RETENTION_MILLIS + 1);
		host.maintain();

		assertThat(first, contains(new Reported(chainId, new Gap(ChainFleet.writer(1), 0, 3,
			Set.of(Fingerprint.NONE)))));
		assertThat(second, equalTo(first));

		host.maintain();
		assertThat(first.size(), is(1));
	}

	@Example
	public void waitsForTheEdgeOfTheRetentionWindowBeforeReportingAGap() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Reported> reported = reportedTo(host);

		receive(fleet, "gn-0", behindAGap(1, fleet.chain()));
		fleet.clock.addAndGet(RETENTION_MILLIS);
		host.maintain();
		assertThat(reported, is(empty()));

		fleet.clock.addAndGet(1);
		host.maintain();
		assertThat(reported, hasSize(1));
	}

	@Example
	public void tellsEveryListenerOncePerGapWithTheChainOfTheGap() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final ChainId first = host.hostedChains().get(0);
		final ChainId third = host.hostedChains().get(2);
		final List<Reported> reported = reportedTo(host);
		final List<Reported> also = reportedTo(host);

		receive(fleet, "gn-0", behindAGap(1, first));
		receive(fleet, "gn-0", behindAGap(2, third));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);

		host.maintain();
		host.maintain();

		assertThat(reported, containsInAnyOrder(new Reported(first, gapOf(1)), new Reported(third, gapOf(2))));
		assertThat(also, equalTo(reported));
	}

	@Example
	public void tellsTheOtherListenersAndLogsOnlyTheClassOfOneThatFails() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");

		host.addGapListener((chain, gap) -> {
			throw new IllegalStateException(DETAIL);
		});

		final List<Reported> reported = reportedTo(host);

		receive(fleet, "gn-0", behindAGap(1, fleet.chain()));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);

		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(host::maintain);

		assertThat(reported, hasSize(1));
		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	public void maintainsTheRetiredChainsToo() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<ChainId> earlier = host.hostedChains();
		final Chain quiet = host.chain(earlier.get(0)).orElseThrow();
		final List<Reported> reported = reportedTo(host);

		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), earlier.get(0), 3, ChainFleet.NOW));
		receive(fleet, "gn-0", behindAGap(2, earlier.get(1)));
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(host.retiredChains(), equalTo(earlier));
		assertThat(quiet.entries().size(), is(3));

		host.maintain();

		assertThat(quiet.entries(), is(empty()));
		assertThat(reported, contains(new Reported(earlier.get(1), gapOf(2))));
	}

	@Example
	public void maintainsOnEveryScheduledTick() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final Chain chain = host.chain(fleet.chain()).orElseThrow();
		final List<Reported> reported = reportedTo(host);

		host.start();
		receive(fleet, "gn-0", ChainFleet.entries(ChainFleet.key(1), chain.id(), 3, ChainFleet.NOW));
		receive(fleet, "gn-0", behindAGap(2, host.hostedChains().get(1)));
		fleet.clock.addAndGet(RETENTION_MILLIS + 1);

		fleet.scheduler("gn-0").scheduled.run();

		assertThat(chain.entries(), is(empty()));
		assertThat(reported, hasSize(1));
	}

	@Example
	public void tellsListenersThatAreAddedWhileItTellsOnTheNextCall() {
		final Listeners<Runnable> listeners = new Listeners<>();
		final AtomicInteger told = new AtomicInteger();

		listeners.add(() -> listeners.add(told::incrementAndGet));
		listeners.tell(Runnable::run);
		listeners.tell(Runnable::run);

		assertThat(told.get(), is(1));
	}

	@Example @SneakyThrows
	public void keepsEveryListenerThatIsAddedWhileOthersAreTold() {
		final Listeners<Runnable> listeners = new Listeners<>();
		final AtomicInteger told = new AtomicInteger();
		final ExecutorService adders = Executors.newFixedThreadPool(THREADS);

		try {
			final List<Future<?>> adding = new ArrayList<>();

			IntStream.range(0, THREADS).forEach(thread -> adding.add(adders.submit(
				() -> IntStream.range(0, ADDS).forEach(add -> listeners.add(told::incrementAndGet)))));

			for (final Future<?> done : adding) {
				while (!done.isDone()) {
					listeners.tell(Runnable::run);
				}

				done.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			adders.shutdownNow();
		}

		told.set(0);
		listeners.tell(Runnable::run);

		assertThat(told.get(), is(THREADS * ADDS));
	}

	@Property(tries = 10)
	public void bringsALateMemberLevelOnceTheEntriesItCouldNotGetExpire(@ForAll long seed) {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);

		fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30, 3)));
		fleet.random.setSeed(seed);
		IntStream.range(0, ChainFleet.MEMBERS - 1).forEach(member -> fleet.host("gn-" + member));
		fleet.refreshAll();

		final ChainId chainId = fleet.chain();
		final FeedWriter writer = new FeedWriter(ChainFleet.key(1), chainId);

		fleet.host("gn-0").receive(fleet.client("gn-0", 1), ChainEntry.builder()
			.entries(ChainFleet.write(writer, 10, ChainFleet.NOW)).build());
		fleet.deliver();
		fleet.clock.addAndGet(20_000);
		fleet.host("gn-1").receive(fleet.client("gn-1", 1), ChainEntry.builder()
			.entries(ChainFleet.write(writer, 5, ChainFleet.NOW + 20_000)).build());
		fleet.deliver();

		fleet.host("gn-3");
		fleet.refreshAll();
		fleet.clock.addAndGet(15_000);
		IntStream.range(0, 20).forEach(round -> fleet.round());

		assertThat(fleet.agree(chainId), is(false));

		fleet.clock.addAndGet(RETENTION_MILLIS);
		fleet.round();

		assertThat(fleet.agree(chainId), is(true));
		assertThat(fleet.host("gn-3").chain(chainId).orElseThrow().heads(), equalTo(fleet.host("gn-0")
			.chain(chainId).orElseThrow().heads()));
	}
}
