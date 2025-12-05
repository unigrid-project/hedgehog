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

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.group.ChainIds;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.proxy.group.ShardGroups;

public class ChainHostingTest {
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final long NOW_INTO_EPOCH = 400_000;
	/* The epoch after the one that ChainFleet.NOW falls in */
	private static final long NEXT_EPOCH = 486_112;
	private static final long NEXT_START = NEXT_EPOCH * EPOCH_MILLIS;
	private static final int SWAPS = 5_000;
	private static final int TIMEOUT_SECONDS = 10;

	/* Counts how often the shard groups are ranked, which is what a refresh must not do for nothing, and notes
	   whether the host was locked each time */
	private static final class CountingHost extends ChainHost {
		private final List<Boolean> locked = new ArrayList<>();
		private int views;

		CountingHost(final ChainFleet fleet, final Supplier<String> own) {
			super(fleet.settings::get, new TopologyGridnodeDirectory(() -> fleet.gridnodes, own), fleet.clock::get);
		}

		@Override
		GroupView viewOf(final ProxySettings current, final long now, final Collection<String> ids) {
			views++;
			locked.add(Thread.holdsLock(this));
			return super.viewOf(current, now, ids);
		}
	}

	private static ChainHost refreshed(final ChainFleet fleet, final String id) {
		final ChainHost host = fleet.host(id);

		host.refresh();
		return host;
	}

	private static ChainHost hostAt(final long now) {
		final ChainFleet fleet = new ChainFleet(20);

		fleet.clock.set(now);
		return refreshed(fleet, "gn-3");
	}

	private static List<ChainId> chainsIn(final long epoch) {
		final Epoch number = new Epoch(epoch);
		final ShardGroups groups = ShardGroups.of(number, 8, new ChainFleet(20).activeIds());

		return ChainIds.ofGroup(number, groups.groupOf("gn-3").orElseThrow(), 4);
	}

	private static void assertRanksAgainAfter(final Consumer<ChainFleet> change) {
		final ChainFleet fleet = new ChainFleet(20);
		final CountingHost host = new CountingHost(fleet, () -> "gn-3");

		host.refresh();

		final Hosting before = host.snapshot();

		change.accept(fleet);
		host.refresh();

		assertThat(host.views, equalTo(2));
		assertThat(host.snapshot(), not(sameInstance(before)));
	}

	private static void assertRanksNothingAgainAfter(final Consumer<ChainFleet> change) {
		final ChainFleet fleet = new ChainFleet(20);
		final CountingHost host = new CountingHost(fleet, () -> "gn-3");

		host.refresh();

		final Hosting before = host.snapshot();

		change.accept(fleet);
		host.refresh();
		host.refresh();

		assertThat(host.views, equalTo(1));
		assertThat(host.snapshot(), sameInstance(before));
	}

	private static ProxySettings withLimits(final long capacityBytes, final int retentionSeconds,
		final long maxEntryBytes) {

		final ProxySettings base = ProxySettings.DEFAULTS;

		return new ProxySettings(base.groupSize(), base.reorganizePeriodSeconds(), base.chainsPerGroup(),
			base.clientsPerChain(), capacityBytes, retentionSeconds, maxEntryBytes, base.sessionIdleSeconds(),
			base.flags());
	}

	private static ProxySettings apartFromLimits(final int clientsPerChain, final int sessionIdleSeconds,
		final int flags) {

		final ProxySettings base = ProxySettings.DEFAULTS;

		return new ProxySettings(base.groupSize(), base.reorganizePeriodSeconds(), base.chainsPerGroup(),
			clientsPerChain, base.chainCapacityBytes(), base.retentionSeconds(), base.maxEntryBytes(),
			sessionIdleSeconds, flags);
	}

	private static void assertRebuildsChainFor(final ProxySettings changed) {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final ChainId first = host.hostedChains().get(0);
		final Chain before = host.chain(first).orElseThrow();

		fleet.settings.set(Optional.of(changed));
		host.refresh();

		assertThat(host.hostedChains().get(0), equalTo(first));
		assertThat(host.chain(first).orElseThrow(), not(sameInstance(before)));
		assertThat(host.chain(first).orElseThrow().limits(), equalTo(changed.chainLimits()));
	}

	private static void assertKeepsChainFor(final ProxySettings changed) {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final ChainId first = host.hostedChains().get(0);
		final Chain before = host.chain(first).orElseThrow();

		fleet.settings.set(Optional.of(changed));
		host.refresh();

		assertThat(host.snapshot().settings(), equalTo(Optional.of(changed)));
		assertThat(host.chain(first).orElseThrow(), sameInstance(before));
	}

	private static void resize(final ChainFleet fleet, final int size) {
		fleet.gridnodes.clear();
		IntStream.range(0, size).forEach(number -> fleet.gridnodes
			.add(ChainFleet.gridnode("gn-" + number, Gridnode.Status.ACTIVE)));
	}

	@Example
	public void hostsNothingWhileTheSettingsAreEmpty() {
		final ChainFleet fleet = new ChainFleet(20);

		fleet.settings.set(Optional.empty());

		final ChainHost host = refreshed(fleet, "gn-3");

		assertThat(host.hostedChains(), is(empty()));
		assertThat(host.group(), equalTo(OptionalInt.empty()));
	}

	@Property(tries = 50)
	public void hostsTheChainsOfTheGroupItIsIn(@ForAll @IntRange(min = 1, max = 40) int size,
		@ForAll @IntRange(min = 1, max = 12) int groupSize, @ForAll @LongRange(min = 0, max = 1L << 40) long now) {

		final ChainFleet fleet = new ChainFleet(size);

		fleet.settings.set(Optional.of(ChainFleet.settings(groupSize, 4, 16, 30, 3)));
		fleet.clock.set(now);

		final GroupView view = fleet.view();

		for (String id : fleet.activeIds()) {
			final ChainHost host = refreshed(fleet, id);

			assertThat(host.group(), equalTo(view.groups().groupOf(id)));
			assertThat(host.hostedChains(), equalTo(view.chainsOf(id)));
		}
	}

	@Example
	public void hostsNothingUnlessItIsAnActiveGridnode() {
		final ChainFleet fleet = new ChainFleet(20);

		fleet.gridnodes.add(ChainFleet.gridnode("sleeper", Gridnode.Status.INACTIVE));

		for (String id : List.of("sleeper", "stranger", " ")) {
			assertThat(refreshed(fleet, id).hostedChains(), is(empty()));
			assertThat(fleet.host(id).group(), equalTo(OptionalInt.empty()));
		}
	}

	@Example
	public void keepsItsChainsWithinAnEpoch() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final ChainId first = host.hostedChains().get(0);
		final Chain before = host.chain(first).orElseThrow();

		fleet.clock.addAndGet(60_000);
		host.refresh();

		assertThat(host.chain(first).orElseThrow(), sameInstance(before));
	}

	@Example
	public void startsNewChainsInTheNextEpoch() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final List<ChainId> before = host.hostedChains();

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(host.hostedChains().size(), equalTo(before.size()));
		assertThat(host.hostedChains(), not(equalTo(before)));
		assertThat(host.chain(before.get(0)), equalTo(Optional.empty()));
	}

	@Example
	public void rebuildsAChainWhoseLimitsChange() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final ChainId first = host.hostedChains().get(0);
		final Chain before = host.chain(first).orElseThrow();

		fleet.settings.set(Optional.of(ChainFleet.settings(8, 4, 16, 31, 3)));
		host.refresh();

		assertThat(host.hostedChains().get(0), equalTo(first));
		assertThat(host.chain(first).orElseThrow(), not(sameInstance(before)));
		assertThat(host.chain(first).orElseThrow().limits().retentionSeconds(), equalTo(31));
	}

	@Example
	public void keepsItsChainsWhenOnlyTheFlagsChange() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final ChainId first = host.hostedChains().get(0);
		final Chain before = host.chain(first).orElseThrow();

		fleet.settings.set(Optional.of(ChainFleet.settings(8, 4, 16, 30, ProxySettings.CONNECT)));
		host.refresh();

		assertThat(host.chain(first).orElseThrow(), sameInstance(before));
	}

	@Example
	public void dropsItsChainsWhenTheSporkGoesAndStartsNewOnesWhenItReturns() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final List<ChainId> hosted = host.hostedChains();
		final Chain before = host.chain(hosted.get(0)).orElseThrow();

		fleet.settings.set(Optional.empty());
		host.refresh();
		assertThat(host.hostedChains(), is(empty()));
		assertThat(host.chain(hosted.get(0)), equalTo(Optional.empty()));

		fleet.settings.set(Optional.of(ProxySettings.DEFAULTS));
		host.refresh();
		assertThat(host.hostedChains(), equalTo(hosted));
		assertThat(host.chain(hosted.get(0)).orElseThrow(), not(sameInstance(before)));
	}

	@Property(tries = 30)
	public void followsTheTopologyWhenItGrowsAndShrinks(@ForAll @IntRange(min = 1, max = 30) int before,
		@ForAll @IntRange(min = 1, max = 30) int after) {

		final ChainFleet fleet = new ChainFleet(before);
		final ChainHost host = refreshed(fleet, "gn-0");
		final Map<ChainId, Chain> kept = host.hostedChains().stream()
			.collect(Collectors.toMap(id -> id, id -> host.chain(id).orElseThrow()));

		resize(fleet, after);
		host.refresh();

		final GroupView view = fleet.view();

		assertThat(host.group(), equalTo(view.groups().groupOf("gn-0")));
		assertThat(host.hostedChains(), equalTo(view.chainsOf("gn-0")));
		host.hostedChains().stream().filter(kept::containsKey)
			.forEach(id -> assertThat(host.chain(id).orElseThrow(), sameInstance(kept.get(id))));
	}

	@Example
	public void followsTheEpochToTheMillisecond() {
		assertThat(hostAt(ChainFleet.NOW).hostedChains(), equalTo(chainsIn(NEXT_EPOCH - 1)));
		assertThat(hostAt(NEXT_START - 1).hostedChains(), equalTo(chainsIn(NEXT_EPOCH - 1)));
		assertThat(hostAt(NEXT_START).hostedChains(), equalTo(chainsIn(NEXT_EPOCH)));
		assertThat(hostAt(NEXT_START + 1).hostedChains(), equalTo(chainsIn(NEXT_EPOCH)));
		assertThat(hostAt(0).hostedChains(), equalTo(chainsIn(0)));
		assertThat(hostAt(EPOCH_MILLIS - 1).hostedChains(), equalTo(chainsIn(0)));
		assertThat(hostAt(EPOCH_MILLIS).hostedChains(), equalTo(chainsIn(1)));
		assertThat(hostAt(Long.MAX_VALUE).hostedChains(), equalTo(chainsIn(Long.MAX_VALUE / EPOCH_MILLIS)));
	}

	@Example
	public void buildsTheEpochOfAClockThatSteppedBackwards() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final Chain current = host.chain(host.hostedChains().get(0)).orElseThrow();

		fleet.clock.set(NEXT_START - EPOCH_MILLIS - 1);
		host.refresh();
		assertThat(host.hostedChains(), equalTo(chainsIn(NEXT_EPOCH - 2)));
		assertThat(host.chain(current.id()), equalTo(Optional.empty()));

		fleet.clock.set(ChainFleet.NOW);
		host.refresh();
		assertThat(host.hostedChains(), equalTo(chainsIn(NEXT_EPOCH - 1)));
		assertThat(host.chain(current.id()).orElseThrow(), not(sameInstance(current)));
	}

	@Example
	public void startsTheNextEpochOnTheMillisecondItBegins() {
		final ChainFleet fleet = new ChainFleet(20);

		fleet.clock.set(NEXT_START - 1);

		final ChainHost host = refreshed(fleet, "gn-3");

		assertThat(host.hostedChains(), equalTo(chainsIn(NEXT_EPOCH - 1)));

		fleet.clock.set(NEXT_START);
		host.refresh();

		assertThat(host.hostedChains(), equalTo(chainsIn(NEXT_EPOCH)));
	}

	@Example
	public void keepsItsChainsWhenTheClockStepsBackWithinTheEpoch() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final ChainId first = host.hostedChains().get(0);
		final Chain before = host.chain(first).orElseThrow();

		fleet.clock.addAndGet(-NOW_INTO_EPOCH);
		host.refresh();

		assertThat(host.chain(first).orElseThrow(), sameInstance(before));
	}

	@Example
	public void hostsNothingWhileTheClockReadsBeforeTheOrigin() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");

		for (final long now : new long[] {-1, Long.MIN_VALUE}) {
			fleet.clock.set(now);
			host.refresh();

			assertThat(host.hostedChains(), is(empty()));
			assertThat(host.group(), equalTo(OptionalInt.empty()));
		}

		fleet.clock.set(ChainFleet.NOW);
		host.refresh();
		assertThat(host.hostedChains(), equalTo(chainsIn(NEXT_EPOCH - 1)));
	}

	@Example
	public void hostsNothingInAnEmptyTopology() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");

		fleet.gridnodes.clear();
		host.refresh();

		assertThat(host.hostedChains(), is(empty()));
		assertThat(host.group(), equalTo(OptionalInt.empty()));
	}

	@Example
	public void ignoresAGridnodeWithoutAnId() {
		final ChainFleet fleet = new ChainFleet(20);
		final List<ChainId> expected = refreshed(new ChainFleet(20), "gn-3").hostedChains();

		fleet.gridnodes.add(Gridnode.builder().status(Gridnode.Status.ACTIVE).build());

		assertThat(refreshed(fleet, "gn-3").hostedChains(), equalTo(expected));
	}

	@Example
	public void hostsNothingWithoutAnIdOfItsOwn() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = new CountingHost(fleet, () -> null);

		host.refresh();

		assertThat(host.hostedChains(), is(empty()));
		assertThat(host.group(), equalTo(OptionalInt.empty()));
	}

	@Example
	public void ranksTheGroupsOnceWhileNothingChanges() {
		assertRanksNothingAgainAfter(fleet -> fleet.clock.addAndGet(1_000));
	}

	@Example
	public void ranksNothingAgainWithinAnEpochWhicheverWayTheClockMoves() {
		assertRanksNothingAgainAfter(fleet -> fleet.clock.addAndGet(-NOW_INTO_EPOCH));
		assertRanksNothingAgainAfter(fleet -> fleet.clock.addAndGet(EPOCH_MILLIS - NOW_INTO_EPOCH - 1));
		assertRanksNothingAgainAfter(fleet -> fleet.clock.addAndGet(-1));
	}

	@Example
	public void ranksNothingAgainForSettingsThatAreEqual() {
		assertRanksNothingAgainAfter(fleet -> fleet.settings.set(Optional.of(ChainFleet.settings(8, 4, 16, 30, 3))));
	}

	@Example
	public void ranksNothingAgainForTheSameActiveGridnodesInAnotherShape() {
		assertRanksNothingAgainAfter(fleet -> Collections.reverse(fleet.gridnodes));
		assertRanksNothingAgainAfter(fleet -> fleet.gridnodes
			.replaceAll(gridnode -> ChainFleet.gridnode(gridnode.getId(), Gridnode.Status.ACTIVE)));
		assertRanksNothingAgainAfter(fleet -> fleet.gridnodes
			.add(ChainFleet.gridnode("gn-3", Gridnode.Status.ACTIVE)));
		assertRanksNothingAgainAfter(fleet -> fleet.gridnodes
			.add(ChainFleet.gridnode("sleeper", Gridnode.Status.INACTIVE)));
	}

	@Example
	public void ranksAgainWhenTheSettingsChange() {
		assertRanksAgainAfter(fleet -> fleet.settings.set(Optional.of(ChainFleet.settings(8, 4, 16, 31, 3))));
		assertRanksAgainAfter(fleet -> fleet.settings.set(Optional.of(ChainFleet.settings(8, 4, 16, 30, 1))));
		assertRanksAgainAfter(fleet -> fleet.settings.set(Optional.of(ChainFleet.settings(7, 4, 16, 30, 3))));
	}

	@Example
	public void ranksAgainWhenTheEpochChanges() {
		assertRanksAgainAfter(fleet -> fleet.clock.addAndGet(EPOCH_MILLIS - NOW_INTO_EPOCH));
		assertRanksAgainAfter(fleet -> fleet.clock.addAndGet(-NOW_INTO_EPOCH - 1));
		assertRanksAgainAfter(fleet -> fleet.clock.addAndGet(EPOCH_MILLIS));
	}

	@Example
	public void ranksAgainWhenTheActiveGridnodesChange() {
		assertRanksAgainAfter(fleet -> fleet.gridnodes.add(ChainFleet.gridnode("newcomer", Gridnode.Status.ACTIVE)));
		assertRanksAgainAfter(fleet -> fleet.gridnodes.remove(fleet.gridnodes.size() - 1));
		assertRanksAgainAfter(fleet -> fleet.gridnodes.get(5).setStatus(Gridnode.Status.INACTIVE));
	}

	@Example
	public void ranksAgainWhenOneActiveGridnodeIsReplacedByAnother() {
		assertRanksAgainAfter(fleet -> fleet.gridnodes
			.set(5, ChainFleet.gridnode("replacement", Gridnode.Status.ACTIVE)));
		assertRanksAgainAfter(fleet -> fleet.gridnodes
			.set(19, ChainFleet.gridnode("gn-20", Gridnode.Status.ACTIVE)));
	}

	@Example
	public void rebuildsAChainWhoseCapacityEntrySizeOrRetentionChanges() {
		final ProxySettings base = ProxySettings.DEFAULTS;

		assertRebuildsChainFor(withLimits(base.chainCapacityBytes() + 1, base.retentionSeconds(), base.maxEntryBytes()));
		assertRebuildsChainFor(withLimits(base.chainCapacityBytes(), base.retentionSeconds(), base.maxEntryBytes() + 1));
		assertRebuildsChainFor(withLimits(base.chainCapacityBytes(), base.retentionSeconds() + 1, base.maxEntryBytes()));
	}

	@Example
	public void keepsItsChainsWhenOnlySettingsOutsideTheLimitsChange() {
		assertKeepsChainFor(apartFromLimits(16, 300, ProxySettings.CONNECT));
		assertKeepsChainFor(apartFromLimits(16, 300, 0));
		assertKeepsChainFor(apartFromLimits(17, 300, 3));
		assertKeepsChainFor(apartFromLimits(16, 301, 3));
	}

	@Example
	public void ranksAgainWhenTheNodeBecomesAnotherGridnode() {
		final ChainFleet fleet = new ChainFleet(20);
		final GroupView view = fleet.view();
		final String first = view.groups().members(0).get(0);
		final String second = view.groups().members(1).get(0);
		final AtomicReference<String> own = new AtomicReference<>(first);
		final CountingHost host = new CountingHost(fleet, own::get);

		host.refresh();
		own.set(second);
		host.refresh();

		assertThat(host.views, equalTo(2));
		assertThat(host.group(), equalTo(OptionalInt.of(1)));
		assertThat(host.hostedChains(), equalTo(view.chains(1)));
	}

	@Example
	public void ranksTheGroupsOnceWhenRefreshesRunAtTheSameTime() throws Exception {
		final int threads = 8;
		final ChainFleet fleet = new ChainFleet(200);
		final CountingHost host = new CountingHost(fleet, () -> "gn-3");
		final CyclicBarrier start = new CyclicBarrier(threads);
		final ExecutorService executor = Executors.newFixedThreadPool(threads);

		try {
			final List<Future<Void>> refreshes = IntStream.range(0, threads).mapToObj(number -> executor.submit(() -> {
				start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				host.refresh();
				return (Void) null;
			})).toList();

			for (final Future<Void> refresh : refreshes) {
				refresh.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(host.views, equalTo(1));
	}

	@Example
	public void ranksTheGroupsWhileHoldingTheLockOfTheHost() {
		final CountingHost host = new CountingHost(new ChainFleet(20), () -> "gn-3");

		host.refresh();

		assertThat(host.locked, equalTo(List.of(true)));
	}

	@Example
	public void keepsTheSettingsItWasBuiltFrom() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");

		assertThat(host.snapshot().settings(), equalTo(Optional.of(ProxySettings.DEFAULTS)));

		fleet.settings.set(Optional.of(ChainFleet.settings(8, 4, 16, 30, ProxySettings.CONNECT)));
		host.refresh();

		assertThat(host.snapshot().settings(), equalTo(fleet.settings.get()));
	}

	@Example
	public void leavesASnapshotAReaderHoldsAsItWasWhenTheHostSwaps() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final Hosting held = host.snapshot();
		final List<ChainId> hosted = host.hostedChains();
		final OptionalInt group = host.group();

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		assertThat(host.snapshot(), not(sameInstance(held)));
		assertThat(host.hostedChains(), not(equalTo(hosted)));
		assertThat(List.copyOf(held.current().keySet()), equalTo(hosted));
		assertThat(held.group(), equalTo(group));
		assertThat(held.find(hosted.get(0)).orElseThrow().getChain().id(), equalTo(hosted.get(0)));
	}

	@Example
	public void hostsSnapshotsNobodyCanChange() {
		final Hosting hosting = refreshed(new ChainFleet(20), "gn-3").snapshot();

		assertThrows(UnsupportedOperationException.class, () -> hosting.current().clear());
		assertThrows(UnsupportedOperationException.class, () -> Hosting.OFF.current().clear());
	}

	@Example
	public void publishesTheSnapshotThroughAVolatileField() throws NoSuchFieldException {
		assertThat(Modifier.isVolatile(ChainHost.class.getDeclaredField("hosting").getModifiers()), is(true));
	}

	@Example
	public void letsReadersSeeOnlyWholeSnapshotsWhileTheHostSwaps() throws Exception {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = refreshed(fleet, "gn-3");
		final List<ChainId> early = host.hostedChains();

		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();

		final List<ChainId> late = host.hostedChains();
		final AtomicBoolean swapping = new AtomicBoolean(true);
		final CompletableFuture<List<List<ChainId>>> reader = CompletableFuture.supplyAsync(() -> {
			final List<List<ChainId>> torn = new ArrayList<>();

			while (swapping.get()) {
				final List<ChainId> seen = host.hostedChains();

				if (!seen.equals(early) && !seen.equals(late)) {
					torn.add(seen);
				}
			}

			return torn;
		});

		try {
			for (int swap = 0; swap < SWAPS; swap++) {
				fleet.clock.addAndGet(swap % 2 == 0 ? -EPOCH_MILLIS : EPOCH_MILLIS);
				host.refresh();
			}
		} finally {
			swapping.set(false);
		}

		assertThat(reader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(empty()));
	}
}
