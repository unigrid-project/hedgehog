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

package org.unigrid.hedgehog.transport.initializer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import org.unigrid.hedgehog.transport.schedule.AbstractSchedule;
import org.unigrid.hedgehog.transport.schedule.Schedulable;

/* The schedules of a stream (ping, peers, sporks) are set up once, as it is made, on a live stream of a QUIC
   connection over loopback. A task of a fixed rate on the event loop is never run again once a run of it throws, and
   says nothing of it, so a run that fails must not get out of the registration: a ping that stopped would leave a
   dead peer open until it idles out. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class RegisterQuicChannelInitializerTest {
	private static final int PERIOD_MILLIS = 50;
	private static final int RUNS = 5;
	private static final Duration PATIENCE = Duration.ofSeconds(10);
	private static final String FAILURE = "A run of the schedule failed";

	/* What the stream asks of the node once its schedules are set up is of no concern here, and there is none to ask */
	@BeforeTry
	public void withoutANode() {
		new MockUp<CDIUtil>() {
			@Mock void resolveAndRun(Class<?> type, Consumer<?> consumer) { }
		};
	}

	private static Schedulable every(int periodMillis, boolean executeOnCreation, Consumer<Channel> consumer) {
		return new AbstractSchedule(periodMillis, TimeUnit.MILLISECONDS, executeOnCreation) {
			@Override
			public Consumer<Channel> getConsumer() {
				return consumer;
			}
		};
	}

	private static RegisterQuicChannelInitializer initializerOf(Schedulable schedule) {
		return new RegisterQuicChannelInitializer(List::of, () -> List.of(schedule),
			RegisterQuicChannelInitializer.Type.SERVER);
	}

	private static List<ILoggingEvent> failuresLogged(ListAppender<ILoggingEvent> appender) {
		synchronized (appender) {
			return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
				.filter(event -> Objects.nonNull(event.getThrowableProxy())
					&& FAILURE.equals(event.getThrowableProxy().getMessage())).toList();
		}
	}

	@Example
	public void runsAScheduleAgainAfterARunThatThrew() throws Exception {
		final AtomicInteger runs = new AtomicInteger();

		try (QuicLoopback loopback = QuicLoopback.between(QuicLoopback.PREVIOUS_RELEASE,
			QuicLoopback.PREVIOUS_RELEASE)) {

			initializerOf(every(PERIOD_MILLIS, true, channel -> {
				if (runs.incrementAndGet() == 1) {
					throw new IllegalStateException(FAILURE);
				}
			})).initChannel(loopback.stream());

			await().atMost(PATIENCE).alias("Runs of a schedule whose first run threw")
				.untilAtomic(runs, greaterThanOrEqualTo(RUNS));
		}
	}

	@Example
	public void logsARunThatThrew() throws Exception {
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(RegisterQuicChannelInitializer.class);

		appender.start();
		logger.addAppender(appender);

		try (QuicLoopback loopback = QuicLoopback.between(QuicLoopback.PREVIOUS_RELEASE,
			QuicLoopback.PREVIOUS_RELEASE)) {

			initializerOf(every(PERIOD_MILLIS, true, channel -> {
				throw new IllegalStateException(FAILURE);
			})).initChannel(loopback.stream());

			await().atMost(PATIENCE).alias("Warnings that carry the failure of a run")
				.until(() -> failuresLogged(appender), hasSize(greaterThanOrEqualTo(1)));
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Example
	public void asksForTheSchedulesOfAStreamOnce() throws Exception {
		final AtomicInteger asked = new AtomicInteger();

		try (QuicLoopback loopback = QuicLoopback.between(QuicLoopback.PREVIOUS_RELEASE,
			QuicLoopback.PREVIOUS_RELEASE)) {

			new RegisterQuicChannelInitializer(List::of, () -> {
				asked.incrementAndGet();
				return List.of(every(PERIOD_MILLIS, false, channel -> { }));
			}, RegisterQuicChannelInitializer.Type.SERVER).initChannel(loopback.stream());

			assertThat(asked.get(), is(1));
		}
	}

	/* The constructor without schedules is for a stream that has none */
	@Example
	public void setsUpAStreamThatHasNoSchedules() throws Exception {
		try (QuicLoopback loopback = QuicLoopback.between(QuicLoopback.PREVIOUS_RELEASE,
			QuicLoopback.PREVIOUS_RELEASE)) {

			final QuicStreamChannel stream = loopback.stream();

			new RegisterQuicChannelInitializer(List::of, RegisterQuicChannelInitializer.Type.SERVER).initChannel(stream);
			assertThat(RegisterQuicChannelInitializer.Type.SERVER.is(stream), is(true));
		}
	}
}
