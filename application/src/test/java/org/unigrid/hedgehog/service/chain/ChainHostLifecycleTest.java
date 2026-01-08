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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.storage.placement.TopologyGridnodeDirectory;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostLifecycleTest {
	private static final String DETAIL = "10.1.2.3:4567";
	private static final int TIMEOUT_SECONDS = 10;
	private static final List<Gridnode> GRIDNODES = new ChainFleet(20).gridnodes;
	private static final List<String> STEPS = List.of("refresh", "maintain", "reconcileRound");
	private static final Map<String, String> LOGGED_AS = Map.of("refresh", "refresh", "maintain", "maintain",
		"reconcileRound", "reconcile");

	/* How the log line names a step of the tick */
	private static String stepNamed(final String step) {
		return "The " + LOGGED_AS.get(step) + " step ";
	}

	/* Notes the steps of a tick in the order they ran, and fails the one it is told to */
	private static final class Steps extends ChainHost {
		private final List<String> ran = new ArrayList<>();
		private final String failing;

		Steps(final String failing) {
			super(() -> Optional.empty(), new TopologyGridnodeDirectory(() -> GRIDNODES, () -> "gn-3"),
				peer -> Optional.empty(), () -> ChainFleet.NOW, new Random(1), new ManualScheduler());

			this.failing = failing;
		}

		@Override
		public void refresh() {
			step("refresh");
		}

		@Override
		public void maintain() {
			step("maintain");
		}

		@Override
		public void reconcileRound() {
			step("reconcileRound");
		}

		private void step(final String name) {
			ran.add(name);

			if (name.equals(failing)) {
				throw new IllegalStateException(DETAIL);
			}
		}
	}

	private static JoinReply.Status joinStatus(final ChainHost host) {
		return host.join(new EmbeddedChannel(), JoinChain.builder().writer(ChainFleet.writer(1)).build()).getStatus();
	}

	private static ChainHost startedOver(final ProxySettingsSource settings,
		final Supplier<Collection<Gridnode>> topology, final ChainTransport transport,
		final ManualScheduler scheduler) {

		final ChainHost host = new ChainHost(settings, new TopologyGridnodeDirectory(topology, () -> "gn-3"),
			transport, () -> ChainFleet.NOW, new Random(1), scheduler);

		host.start();
		return host;
	}

	private static ProxySettingsSource failingWhile(final AtomicBoolean failing) {
		return () -> {
			if (failing.get()) {
				throw new IllegalStateException(DETAIL);
			}

			return Optional.of(ProxySettings.DEFAULTS);
		};
	}

	private static ChainHost startedOver(final ProxySettingsSource settings, final ManualScheduler scheduler) {
		return startedOver(settings, () -> GRIDNODES, peer -> Optional.empty(), scheduler);
	}

	@Example
	public void ticksOnceASecondOnItsOwnScheduler() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.host("gn-3");

		host.start();

		assertThat(fleet.scheduler("gn-3").schedules, is(1));
		assertThat(fleet.scheduler("gn-3").initialDelayMillis, is(1000L));
		assertThat(fleet.scheduler("gn-3").periodMillis, is(1000L));
		assertThat(host.hostedChains(), is(empty()));

		fleet.scheduler("gn-3").scheduled.run();
		assertThat(host.hostedChains().size(), is(4));
	}

	@Example
	public void shutsItsSchedulerDownWhenTheDaemonStops() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.host("gn-3");

		host.start();
		assertThat(fleet.scheduler("gn-3").isShutdown(), is(false));

		host.stop();

		assertThat(fleet.scheduler("gn-3").isShutdown(), is(true));
	}

	@Example
	public void stopsTwiceAndTicksAfterItWithoutHarm() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.host("gn-3");

		host.start();
		host.stop();
		host.stop();
		host.tick();

		assertThat(fleet.scheduler("gn-3").isShutdown(), is(true));
		assertThat(fleet.scheduler("gn-3").schedules, is(1));
		assertThat(host.hostedChains().size(), is(4));
	}

	@Example
	public void isOffWhileTheSporkIsAbsentAndComesBackWithIt() {
		final ChainFleet fleet = new ChainFleet(20);
		final ChainHost host = fleet.host("gn-3");

		fleet.settings.set(Optional.empty());
		host.tick();
		assertThat(joinStatus(host), is(JoinReply.Status.DISABLED));
		assertThat(host.hostedChains(), is(empty()));

		fleet.settings.set(Optional.of(ProxySettings.DEFAULTS));
		host.tick();
		assertThat(joinStatus(host), is(JoinReply.Status.JOINED));
	}

	@Example
	public void goesOnTickingAfterTheSettingsSourceFails() {
		final AtomicBoolean failing = new AtomicBoolean(true);
		final ManualScheduler scheduler = new ManualScheduler();
		final ChainHost host = startedOver(failingWhile(failing), scheduler);

		scheduler.scheduled.run();
		assertThat(host.hostedChains(), is(empty()));

		failing.set(false);
		scheduler.scheduled.run();
		assertThat(host.hostedChains().size(), is(4));
	}

	@Example
	public void goesOnTickingAfterTheDirectoryFails() {
		final AtomicBoolean failing = new AtomicBoolean(true);
		final ManualScheduler scheduler = new ManualScheduler();
		final ChainHost host = startedOver(() -> Optional.of(ProxySettings.DEFAULTS), () -> {
			if (failing.get()) {
				throw new IllegalStateException(DETAIL);
			}

			return GRIDNODES;
		}, peer -> Optional.empty(), scheduler);

		scheduler.scheduled.run();
		assertThat(host.hostedChains(), is(empty()));

		failing.set(false);
		scheduler.scheduled.run();
		assertThat(host.hostedChains().size(), is(4));
	}

	@Example
	public void goesOnTickingAfterTheTransportFails() {
		final AtomicBoolean failing = new AtomicBoolean(true);
		final ManualScheduler scheduler = new ManualScheduler();
		final ChainHost host = startedOver(() -> Optional.of(ProxySettings.DEFAULTS), () -> GRIDNODES, peer -> {
			if (failing.get()) {
				throw new IllegalStateException(DETAIL);
			}

			return Optional.of(new EmbeddedChannel());
		}, scheduler);

		scheduler.scheduled.run();
		assertThat(host.hostedChains().size(), is(4));
		assertThat(host.hosted(host.hostedChains().get(0)).orElseThrow().getMemberStreams(), is(empty()));

		failing.set(false);
		scheduler.scheduled.run();
		assertThat(host.hosted(host.hostedChains().get(0)).orElseThrow().getMemberStreams(), not(empty()));
	}

	@Example
	public void logsOnlyTheClassOfWhatFailedInATick() {
		final ManualScheduler scheduler = new ManualScheduler();

		startedOver(failingWhile(new AtomicBoolean(true)), scheduler);

		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(scheduler.scheduled);

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getLevel(), is(Level.WARN));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(stepNamed("refresh")));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	public void runsTheStepsOfATickInOrder() {
		final Steps host = new Steps("none");

		host.tick();

		assertThat(host.ran, equalTo(STEPS));
	}

	@Example
	public void runsTheStepsAfterOneThatFails() {
		for (final String failing : STEPS) {
			final Steps host = new Steps(failing);

			final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(host::tick);

			assertThat(failing, host.ran, equalTo(STEPS));
			assertThat(failing, warnings, hasSize(1));
			assertThat(failing, warnings.get(0).getFormattedMessage(), not(containsString(DETAIL)));
			assertThat(failing, warnings.get(0).getFormattedMessage(), containsString(stepNamed(failing)));
			STEPS.stream().filter(other -> !other.equals(failing)).forEach(other -> assertThat(failing,
				warnings.get(0).getFormattedMessage(), not(containsString(stepNamed(other)))));
		}
	}

	@Example
	public void logsTheClassOfAnErrorAndLetsItPassThrough() {
		final ManualScheduler scheduler = new ManualScheduler();

		startedOver(() -> {
			throw new LinkageError(DETAIL);
		}, scheduler);

		final List<ILoggingEvent> logged = ChainFleet.warningsWhile(() -> assertThrows(LinkageError.class,
			scheduler.scheduled::run));

		assertThat(logged, hasSize(1));
		assertThat(logged.get(0).getLevel(), is(Level.ERROR));
		assertThat(logged.get(0).getFormattedMessage(), containsString(stepNamed("refresh")));
		assertThat(logged.get(0).getFormattedMessage(), containsString(LinkageError.class.getName()));
		assertThat(logged.get(0).getFormattedMessage(), not(containsString(DETAIL)));
		assertThat(logged.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example @SneakyThrows
	public void ticksOnADaemonThreadOfItsOwnThatEndsWhenTheHostStops() {
		final BlockingQueue<Thread> ticked = new LinkedBlockingQueue<>();
		final ChainHost host = new ChainHost(() -> {
			ticked.add(Thread.currentThread());
			return Optional.empty();
		}, new TopologyGridnodeDirectory(() -> GRIDNODES, () -> "gn-3"), peer -> Optional.empty());

		host.start();

		try {
			final Thread ticker = ticked.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

			assertThat(ticker, is(notNullValue()));
			assertThat(ticker, not(equalTo(Thread.currentThread())));
			assertThat(ticker.isDaemon(), is(true));

			host.stop();
			ticker.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

			assertThat(ticker.isAlive(), is(false));
		} finally {
			host.stop();
		}
	}
}
