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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.group.GroupView;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostDrainTest {
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final long RETENTION_MILLIS = 30_000;
	private static final long GRACE_MILLIS = ChainHost.DRAIN_GRACE_MILLIS;
	private static final String MEMBER = "gn-0";
	private static final int WAIT_SECONDS = 5;
	private static final String FAILURE_DETAIL = "10.9.8.7:52883 turned the stream down";

	/* Sessions of this node on some chains, which note what the host told them */
	private static final class Sessions implements ChainDrain {
		private final ChainHost host;
		private final Set<ChainId> running = ConcurrentHashMap.newKeySet();
		private final List<ChainId> expired = new CopyOnWriteArrayList<>();
		private final List<Boolean> hostedWhenTold = new CopyOnWriteArrayList<>();
		private final List<Boolean> lockedWhenAsked = new CopyOnWriteArrayList<>();
		private volatile Runnable meanwhile = () -> { };

		private Sessions(final ChainHost host, final ChainId... running) {
			this.host = host;
			this.running.addAll(List.of(running));
		}

		@Override
		public boolean holds(final ChainId chainId) {
			lockedWhenAsked.add(Thread.holdsLock(host));
			return running.contains(chainId);
		}

		/* What runs meanwhile runs once, at the first time the host tells */
		@Override
		public void expired(final ChainId chainId) {
			final Runnable once = meanwhile;

			lockedWhenAsked.add(Thread.holdsLock(host));
			expired.add(chainId);
			hostedWhenTold.add(host.chain(chainId).isPresent());
			meanwhile = () -> { };
			once.run();
		}
	}

	/* Sessions that never end on their own: they hold every chain, and the end fails as long as it is made to */
	private static final class Failing implements ChainDrain {
		private final Runnable failure;
		private boolean fails = true;
		private int told;

		private Failing(final Runnable failure) {
			this.failure = failure;
		}

		@Override
		public boolean holds(final ChainId chainId) {
			return true;
		}

		@Override
		public void expired(final ChainId chainId) {
			told++;

			if (fails) {
				failure.run();
			}
		}
	}

	/* The chains of the epoch that ends, retired by the epoch after it */
	private static List<ChainId> retire(final ChainFleet fleet) {
		final List<ChainId> first = fleet.host(MEMBER).hostedChains();

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.host(MEMBER).refresh();
		return first;
	}

	private static ProxySettings withPeriod(final long periodSeconds) {
		return new ProxySettings(ChainFleet.MEMBERS, periodSeconds, 4, 16, 32L * 1024 * 1024, 30, 16 * 1024, 300,
			ProxySettings.CONNECT | ProxySettings.UDP);
	}

	/* A group with one chain, so that the epoch that ends retires that chain alone */
	private static ChainFleet oneChain() {
		return ChainFleet.group(ChainFleet.settings(ChainFleet.MEMBERS, 1, 16, 30, 3));
	}

	/* The one chain of an epoch that ended keeps a session, which its drain says, and is still held once the
	   retention window is over */
	private static ChainId drainingAlone(final ChainFleet fleet, final Sessions sessions) {
		final ChainId first = fleet.chain();

		sessions.running.add(first);
		fleet.host(MEMBER).addDrain(sessions);
		retire(fleet);
		fleet.clock.addAndGet(RETENTION_MILLIS);
		fleet.host(MEMBER).drain();
		assertThat(fleet.host(MEMBER).retiredChains(), contains(first));
		return first;
	}

	/* An exit stems the entries of a session that drains on a retired chain to any member of the group, so every
	   member keeps the chain while a session may drain on it, whether anything runs on it here or not */
	@Example
	public void keepsARetiredChainNothingRunsOnUntilItsGraceIsOver() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final List<ChainId> first = retire(fleet);

		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(RETENTION_MILLIS);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(GRACE_MILLIS - RETENTION_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(host.hostedChains().size(), is(first.size()));
	}

	@Example
	public void keepsAChainASessionRunsOnUntilTheGraceIsOverAndEndsTheSessionFirst() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final List<ChainId> first = fleet.host(MEMBER).hostedChains();
		final Sessions sessions = new Sessions(host, first.get(0));

		host.addDrain(sessions);
		retire(fleet);
		fleet.clock.addAndGet(RETENTION_MILLIS);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(ChainHost.DRAIN_GRACE_MILLIS - RETENTION_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));
		assertThat(sessions.expired, is(empty()));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(sessions.expired, equalTo(first));
		assertThat(sessions.hostedWhenTold, everyItem(is(true)));
	}

	/* While the spork is absent the proxy is off and nothing drains, so the chains nothing runs on go once the
	   retention window is over, and one a client is attached to stays until the client leaves */
	@Example
	public void keepsAChainAClientIsAttachedToWhileTheSporkIsAbsentUntilItLeaves() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final ChainId attached = fleet.chain();
		final EmbeddedChannel client = fleet.client(MEMBER, 2);

		retire(fleet);
		fleet.settings.set(Optional.empty());
		fleet.clock.addAndGet(RETENTION_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains().size(), is(host.hostedChains().size()));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), contains(attached));

		client.close();
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
	}

	@Example
	public void neverKeepsAChainLongerThanTheEpochOfTheSpork() {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 60, 4, 16, 32L * 1024 * 1024,
			30, 16 * 1024, 300, ProxySettings.CONNECT | ProxySettings.UDP));
		final ChainHost host = fleet.host(MEMBER);
		final ChainId chain = fleet.chain();
		final Sessions sessions = new Sessions(host, chain);

		host.addDrain(sessions);
		fleet.clock.addAndGet(60_000);
		host.refresh();
		fleet.clock.addAndGet(59_999);
		host.drain();
		assertThat(host.retiredChains(), hasItem(chain));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(sessions.expired, hasItem(chain));
		assertThat(ChainHost.graceMillis(fleet.settings.get().orElseThrow()), is(60_000L));
		assertThat(ChainHost.graceMillis(ProxySettings.DEFAULTS), is(ChainHost.DRAIN_GRACE_MILLIS));
	}

	@Example
	public void drainsOnEveryTickOfTheHost() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);

		retire(fleet);
		fleet.clock.addAndGet(GRACE_MILLIS);
		host.tick();

		assertThat(host.retiredChains(), is(empty()));
	}

	@Example
	public void takesTheLesserOfTenMinutesAndTheReorganizePeriodForTheGrace() {
		assertThat(ChainHost.DRAIN_GRACE_MILLIS, is(600_000L));
		assertThat(ChainHost.graceMillis(withPeriod(60)), is(60_000L));
		assertThat(ChainHost.graceMillis(withPeriod(599)), is(599_000L));
		assertThat(ChainHost.graceMillis(withPeriod(600)), is(600_000L));
		assertThat(ChainHost.graceMillis(withPeriod(601)), is(600_000L));
		assertThat(ChainHost.graceMillis(withPeriod(3600)), is(600_000L));
	}

	/* The grace rule drops any retired chain, one that a client keeps too */
	@Example
	public void endsAndDropsAChainAClientIsAttachedToOnceTheGraceIsOver() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final ChainId attached = fleet.chain();
		final Sessions sessions = new Sessions(host);

		fleet.client(MEMBER, 2);
		host.addDrain(sessions);

		final List<ChainId> first = retire(fleet);

		fleet.clock.addAndGet(GRACE_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));
		assertThat(sessions.expired, is(empty()));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(sessions.expired, equalTo(first));
		assertThat(first, hasItem(attached));
		assertThat(sessions.hostedWhenTold, everyItem(is(true)));
	}

	@Example
	public void tellsTheDrainsOfAllChainsWhoseGraceIsOverAndKeepsTheCurrentOnes() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host);

		host.addDrain(sessions);

		final List<ChainId> first = retire(fleet);
		final List<ChainId> current = host.hostedChains();

		fleet.clock.addAndGet(GRACE_MILLIS);
		host.drain();

		assertThat(sessions.expired, equalTo(first));
		assertThat(sessions.hostedWhenTold, everyItem(is(true)));
		assertThat(host.retiredChains(), is(empty()));
		assertThat(host.hostedChains(), equalTo(current));
	}

	/* A refresh reuses a hosting the drain took chains from, as the drain keeps its basis and its current chains */
	@Example
	public void dropsOnlyByItsRulesOnATickThatReusesTheHosting() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final List<ChainId> first = retire(fleet);
		final List<ChainId> second = retire(fleet);
		final Hosting before = host.snapshot();

		host.tick();

		final Hosting after = host.snapshot();

		assertThat(host.retiredChains(), equalTo(second));
		assertThat(first.stream().map(host::chain).flatMap(Optional::stream).toList(), is(empty()));
		assertThat(after, not(sameInstance(before)));
		assertThat(after.current(), sameInstance(before.current()));
		assertThat(after.basis(), equalTo(before.basis()));
		assertThat(before.group().isPresent(), is(true));
		assertThat(after.group(), equalTo(before.group()));
		assertThat(after.peers(), equalTo(before.peers()));

		host.refresh();
		assertThat(host.snapshot(), sameInstance(after));
	}

	/* Nothing goes inside the retention window, and a drain that drops nothing leaves the hosting as it was, so no
	   tick pays for a new one */
	@Example
	public void leavesTheHostingAloneWhenNothingGoes() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);

		host.drain();
		assertThat(host.snapshot().retired().isEmpty(), is(true));

		retire(fleet);

		final Hosting before = host.snapshot();

		fleet.clock.addAndGet(RETENTION_MILLIS - 1);
		host.drain();
		assertThat(host.snapshot(), sameInstance(before));
	}

	private static List<Long> retiredAt(final ChainHost host) {
		return host.snapshot().retired().values().stream().map(HostedChain::getRetiredAt).toList();
	}

	/* A refresh that reuses the hosting and a rebuild that keeps the chains retired leave the time they retired */
	@Example
	public void keepsTheTimeAChainRetiredOverRefreshesAndRebuilds() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final List<ChainId> first = retire(fleet);
		final long retired = fleet.clock.get();

		assertThat(retiredAt(host), everyItem(is(retired)));

		fleet.clock.addAndGet(RETENTION_MILLIS / 2);
		host.refresh();
		fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30, ProxySettings.CONNECT)));
		host.refresh();
		assertThat(retiredAt(host), everyItem(is(retired)));

		fleet.clock.set(retired + GRACE_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
	}

	@Example
	public void stampsAChainAnewWhenItRetiresAgainAfterItWasTakenBack() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final List<ChainId> first = retire(fleet);
		final long again = fleet.clock.get() + RETENTION_MILLIS;

		fleet.clock.addAndGet(RETENTION_MILLIS - 1 - EPOCH_MILLIS);
		host.refresh();
		assertThat(host.hostedChains(), equalTo(first));

		fleet.clock.set(again);
		host.refresh();
		assertThat(host.retiredChains(), equalTo(first));
		assertThat(retiredAt(host), everyItem(is(again)));

		host.drain();
		assertThat(host.retiredChains(), equalTo(first));
	}

	/* The tick refreshes first, so a spork that is gone has turned the hosting off before the drain looks: the proxy
	   is off and there is nothing to drain */
	@Example
	public void tellsNoDrainOnceTheSporkIsAbsent() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host, fleet.chain());

		host.addDrain(sessions);
		retire(fleet);
		fleet.clock.addAndGet(GRACE_MILLIS);
		fleet.settings.set(Optional.empty());
		host.tick();

		assertThat(sessions.expired, is(empty()));
		assertThat(host.retiredChains(), is(empty()));
		assertThat(host.hostedChains(), is(empty()));
	}

	/* The grace is the one of the spork as it reads now, whatever the hosting was built with */
	@Example
	public void followsTheGraceOfTheSporkAsItReadsNow() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final List<ChainId> first = retire(fleet);

		fleet.settings.set(Optional.of(withPeriod(120)));
		fleet.clock.addAndGet(119_999);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
	}

	/* Without a spork the proxy is off, even when the hosting is not off yet: no drain is told, and only what nothing
	   runs on goes */
	@Example
	public void tellsNoDrainWhileTheSporkIsAbsentAndDropsOnlyWhatNothingRunsOn() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host, fleet.chain());

		host.addDrain(sessions);

		final List<ChainId> first = retire(fleet);

		fleet.clock.addAndGet(GRACE_MILLIS);
		fleet.settings.set(Optional.empty());
		host.drain();

		assertThat(host.retiredChains(), contains(first.get(0)));
		assertThat(sessions.expired, is(empty()));
	}

	/* A spork that cannot be read means the proxy is off too: no grace runs out, the chains nothing runs on go by the
	   retention window the hosting was built with, and once the spork reads again a grace that is over ends at once */
	@Example
	public void dropsByTheRetentionWindowAloneWhileTheSporkCannotBeRead() {
		final ChainFleet fleet = ChainFleet.group();
		final AtomicBoolean unreadable = new AtomicBoolean();
		final ChainHost host = new ChainHost(() -> {
			if (unreadable.get()) {
				throw new IllegalStateException("The spork cannot be read");
			}

			return fleet.settings.get();
		}, fleet.directory(MEMBER), fleet.transport(MEMBER), fleet.clock::get, new Random(1), new ManualScheduler());
		final ChainId kept = fleet.chain();
		final Sessions sessions = new Sessions(host, kept);

		host.refresh();
		host.addDrain(sessions);
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();
		unreadable.set(true);

		fleet.clock.addAndGet(RETENTION_MILLIS);
		host.tick();
		assertThat(host.retiredChains(), contains(kept));

		fleet.clock.addAndGet(GRACE_MILLIS - RETENTION_MILLIS);
		host.tick();
		assertThat(host.retiredChains(), contains(kept));
		assertThat(sessions.expired, is(empty()));

		unreadable.set(false);
		host.tick();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(sessions.expired, equalTo(List.of(kept)));
	}

	/* The refresh of the tick warns of a spork that cannot be read, so the drain after it tells the same failure at
	   debug and nothing more, by the class of the failure alone */
	@Example
	public void tellsAnUnreadableSporkAtDebugInTheDrainAfterTheRefreshWarnedOfIt() {
		final ChainFleet fleet = ChainFleet.group();
		final AtomicBoolean unreadable = new AtomicBoolean();
		final ChainHost host = new ChainHost(() -> {
			if (unreadable.get()) {
				throw new IllegalStateException(FAILURE_DETAIL);
			}

			return fleet.settings.get();
		}, fleet.directory(MEMBER), fleet.transport(MEMBER), fleet.clock::get, new Random(1), new ManualScheduler());

		host.refresh();
		host.addDrain(new Sessions(host, fleet.chain()));
		fleet.clock.addAndGet(EPOCH_MILLIS);
		host.refresh();
		unreadable.set(true);

		final List<ILoggingEvent> logged = loggedWhile(host::tick);
		final List<ILoggingEvent> warnings = logged.stream().filter(event -> event.getLevel() == Level.WARN).toList();
		final List<ILoggingEvent> debugs = logged.stream().filter(event -> event.getLevel() == Level.DEBUG).toList();

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString("refresh"));
		assertThat(debugs, hasSize(1));
		assertThat(debugs.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(debugs.get(0).getFormattedMessage(), not(containsString(FAILURE_DETAIL)));
		assertThat(debugs.get(0).getThrowableProxy(), is(nullValue()));
	}

	/* What the host tells the log at debug and above while the action runs */
	private static List<ILoggingEvent> loggedWhile(final Runnable action) {
		ChainHostDrainTest.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(ChainHost.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		final Level level = logger.getLevel();

		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(level);
		}

		return List.copyOf(appender.list);
	}

	/* Whether the node is given other chains when the gridnode leaves */
	private static boolean moves(final ChainFleet fleet, final String own, final String leaving) {
		final List<String> rest = fleet.activeIds().stream().filter(id -> !id.equals(leaving)).toList();

		return !new GroupView(ProxySettings.DEFAULTS, fleet.clock.get(), rest).chainsOf(own)
			.equals(fleet.view().chainsOf(own));
	}

	/* The tick refreshes before it drains, so a chain that the refresh of the same tick takes back, as a gridnode
	   that left comes back within the epoch, is current again when the drain looks and keeps its entries */
	@Example
	public void leavesAChainTheRefreshOfTheSameTickTookBack() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.host("gn-3");

		host.refresh();

		final List<ChainId> first = host.hostedChains();
		final Chain chain = host.chain(first.get(0)).orElseThrow();
		final Sessions sessions = new Sessions(host, first.get(0));
		final Gridnode leaving = fleet.gridnodes.stream().filter(gridnode -> !"gn-3".equals(gridnode.getId())
			&& moves(fleet, "gn-3", gridnode.getId())).findFirst().orElseThrow();

		host.addDrain(sessions);
		fleet.gridnodes.remove(leaving);
		host.refresh();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(GRACE_MILLIS);
		fleet.gridnodes.add(leaving);
		host.tick();

		assertThat(host.hostedChains(), equalTo(first));
		assertThat(host.chain(first.get(0)).orElseThrow(), sameInstance(chain));
		assertThat(sessions.expired, is(empty()));
	}

	/* A refresh that holds the host does not keep the drains from being told, and the swap waits for it */
	@Example
	public void tellsTheDrainsWhileARefreshHoldsTheHostAndTakesItsLockForTheSwapAlone() throws Exception {
		final ChainFleet fleet = oneChain();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host);
		final ChainId first = drainingAlone(fleet, sessions);
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicReference<Thread> draining = new AtomicReference<>();
		final ExecutorService threads = Executors.newFixedThreadPool(2);

		fleet.clock.addAndGet(GRACE_MILLIS - RETENTION_MILLIS);

		try {
			threads.submit(() -> {
				synchronized (host) {
					held.countDown();
					return release.await(WAIT_SECONDS, TimeUnit.SECONDS);
				}
			});
			assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

			final Future<?> drained = threads.submit(() -> {
				draining.set(Thread.currentThread());
				host.drain();
			});

			await().atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> !sessions.expired.isEmpty()
				&& draining.get().getState() == Thread.State.BLOCKED);
			assertThat(host.retiredChains(), contains(first));

			release.countDown();
			drained.get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertThat(host.retiredChains(), is(empty()));
		} finally {
			release.countDown();
			threads.shutdownNow();
		}
	}

	@Example
	public void asksAndTellsTheDrainsWithNoLockOfTheHostHeld() {
		final ChainFleet fleet = oneChain();
		final Sessions sessions = new Sessions(fleet.host(MEMBER));

		drainingAlone(fleet, sessions);
		fleet.clock.addAndGet(GRACE_MILLIS);
		fleet.host(MEMBER).tick();

		assertThat(sessions.expired.size(), is(1));
		assertThat(sessions.lockedWhenAsked.isEmpty(), is(false));
		assertThat(sessions.lockedWhenAsked, everyItem(is(false)));
	}

	/* A drain that fails is told again at the next tick, and so is every other, as a chain goes only once every drain
	   ended its sessions on it */
	@Example
	public void tellsEveryDrainWhenOneFailsAndTellsThemAgainAtTheNextTick() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Failing failing = new Failing(() -> {
			throw new IllegalStateException("The drain failed");
		});
		final Sessions sessions = new Sessions(host);

		host.addDrain(failing);
		host.addDrain(sessions);

		final List<ChainId> first = retire(fleet);

		fleet.clock.addAndGet(GRACE_MILLIS);
		host.drain();
		assertThat(sessions.expired, equalTo(first));
		assertThat(host.retiredChains(), equalTo(first));

		failing.fails = false;
		host.drain();
		assertThat(failing.told, is(2 * first.size()));
		assertThat(sessions.expired.size(), is(2 * first.size()));
		assertThat(sessions.hostedWhenTold, everyItem(is(true)));
		assertThat(host.retiredChains(), is(empty()));
	}

	@Example
	public void tellsEveryDrainWhenOneEndsWithAnErrorAndThrowsItAfterwards() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Failing failing = new Failing(() -> {
			throw new StackOverflowError();
		});
		final Sessions sessions = new Sessions(host);

		host.addDrain(failing);
		host.addDrain(sessions);

		final List<ChainId> first = retire(fleet);

		fleet.clock.addAndGet(GRACE_MILLIS);
		Expect.assertThrows(StackOverflowError.class, host::drain);
		assertThat(sessions.expired, equalTo(List.of(first.get(0))));
		assertThat(host.retiredChains(), equalTo(first));

		failing.fails = false;
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
	}

	/* A drain that keeps failing is told at every tick until the chain has been retired for two graces */
	@Example
	public void dropsAChainWhoseDrainKeepsFailingOnceItHasBeenRetiredForTwoGraces() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Failing failing = new Failing(() -> {
			throw new IllegalStateException("The drain failed");
		});

		host.addDrain(failing);

		final List<ChainId> first = retire(fleet);

		fleet.clock.addAndGet(ChainHost.GRACES_UNTIL_DROPPED * GRACE_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(failing.told, is(2 * first.size()));
		assertThat(ChainHost.GRACES_UNTIL_DROPPED, is(2));
	}

	/* The question is whether to keep the chain, so a drain that cannot answer keeps it until the grace is over */
	@Example
	public void keepsAChainADrainCannotSayWhetherItHoldsUntilTheGraceIsOver() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host);

		host.addDrain(new ChainDrain() {
			@Override
			public boolean holds(final ChainId chainId) {
				throw new IllegalStateException("The drain failed");
			}

			@Override
			public void expired(final ChainId chainId) {
				/* Nothing runs to be ended */
			}
		});
		host.addDrain(sessions);

		final List<ChainId> first = retire(fleet);

		fleet.clock.addAndGet(GRACE_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains(), equalTo(first));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(sessions.expired, equalTo(first));
	}

	/* A refresh that rebuilds the hosting while the drains are told keeps what it retired, and the chains that were
	   told go at the next tick, which tells them again */
	@Example
	public void leavesTheDropToTheNextTickWhenARefreshRebuiltTheHostingMeanwhile() {
		final ChainFleet fleet = oneChain();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host);
		final ChainId first = drainingAlone(fleet, sessions);
		final List<ChainId> second = host.hostedChains();

		sessions.meanwhile = () -> {
			fleet.clock.addAndGet(EPOCH_MILLIS);
			host.refresh();
		};
		fleet.clock.addAndGet(GRACE_MILLIS - RETENTION_MILLIS);
		host.drain();

		final List<ChainId> both = new ArrayList<>(List.of(first));

		both.addAll(second);
		assertThat(host.retiredChains(), equalTo(both));

		host.drain();
		assertThat(host.retiredChains(), equalTo(second));
		assertThat(sessions.expired, equalTo(List.of(first, first)));
	}

	/* A refresh that takes a chain back while the drains are told leaves it with the current ones */
	@Example
	public void leavesAChainARefreshTookBackMeanwhileWithTheCurrentOnes() {
		final ChainFleet fleet = oneChain();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host);
		final ChainId first = drainingAlone(fleet, sessions);
		final Chain chain = host.chain(first).orElseThrow();
		final List<ChainId> second = host.hostedChains();

		sessions.meanwhile = () -> {
			fleet.clock.addAndGet(-EPOCH_MILLIS);
			host.refresh();
		};
		fleet.clock.addAndGet(GRACE_MILLIS - RETENTION_MILLIS);
		host.drain();
		host.drain();

		assertThat(host.hostedChains().get(0), equalTo(first));
		assertThat(host.chain(first).orElseThrow(), sameInstance(chain));
		assertThat(host.retiredChains(), equalTo(second));
		assertThat(sessions.expired, equalTo(List.of(first)));
	}

	/* A chain taken back and retired again while the drains are told has retired anew: it is not dropped on what was
	   decided before, and it is kept for its grace from its new retirement, whose end the drains are told again */
	@Example
	public void startsOverWithAChainARefreshTookBackAndRetiredAgainMeanwhile() {
		final ChainFleet fleet = oneChain();
		final ChainHost host = fleet.host(MEMBER);
		final Sessions sessions = new Sessions(host);
		final ChainId first = drainingAlone(fleet, sessions);

		sessions.running.clear();
		sessions.meanwhile = () -> {
			fleet.clock.addAndGet(-EPOCH_MILLIS);
			host.refresh();
			fleet.clock.addAndGet(EPOCH_MILLIS);
			host.refresh();
		};
		fleet.clock.addAndGet(GRACE_MILLIS - RETENTION_MILLIS);
		host.drain();
		assertThat(host.retiredChains().contains(first), is(true));

		fleet.clock.addAndGet(GRACE_MILLIS - 1);
		host.drain();
		assertThat(host.retiredChains().contains(first), is(true));

		fleet.clock.addAndGet(1);
		host.drain();
		assertThat(host.retiredChains(), is(empty()));
		assertThat(sessions.expired, equalTo(List.of(first, first)));
	}
}
