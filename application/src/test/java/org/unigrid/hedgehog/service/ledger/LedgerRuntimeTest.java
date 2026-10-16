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

package org.unigrid.hedgehog.service.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.ledger.Scheduler.Cancellable;

public class LedgerRuntimeTest {
	private static final int TASKS = 2_000;
	private static final long STOP_MILLIS = 5_000;
	private static final InheritableThreadLocal<String> CONTEXT = new InheritableThreadLocal<>();

	private static List<Thread> threadsNamed(final String name) {
		return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals(name)
			&& thread.isAlive()).toList();
	}

	private static String unique() {
		return "ledger-test-" + System.nanoTime();
	}

	@SneakyThrows
	private static <T> T from(final ExecutorService outsider, final Callable<T> action) {
		return outsider.submit(action).get(STOP_MILLIS, TimeUnit.MILLISECONDS);
	}

	@Example
	@SneakyThrows
	public void shouldRunTasksOneAtATimeInTheOrderTheyAreGivenOnAThreadOfItsOwn() {
		final String name = unique();
		final List<Integer> order = new ArrayList<>();
		final AtomicReference<String> thread = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);

		try (LedgerRuntime runtime = new LedgerRuntime(name)) {
			for (int n = 0; n < TASKS; n++) {
				final int number = n;

				runtime.execute(() -> {
					order.add(number);
					thread.set(Thread.currentThread().getName());
				});
			}

			runtime.execute(done::countDown);
			assertThat(done.await(STOP_MILLIS, TimeUnit.MILLISECONDS), is(true));
		}

		assertThat(order, hasSize(TASKS));
		assertThat(order, equalTo(IntStream.range(0, TASKS).boxed().toList()));
		assertThat(thread.get(), equalTo(name + "-events"));
	}

	@Example
	@SneakyThrows
	public void shouldRunATimerOnceAfterItsDelayOnAThreadOfItsOwn() {
		final String name = unique();
		final AtomicReference<String> thread = new AtomicReference<>();
		final CountDownLatch fired = new CountDownLatch(1);

		try (LedgerRuntime runtime = new LedgerRuntime(name)) {
			final long before = System.nanoTime();

			runtime.schedule(() -> {
				thread.set(Thread.currentThread().getName());
				fired.countDown();
			}, 100);

			assertThat(fired.await(STOP_MILLIS, TimeUnit.MILLISECONDS), is(true));
			assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) >= 100, is(true));
		}

		assertThat(thread.get(), equalTo(name + "-timers"));
	}

	@Example
	@SneakyThrows
	public void shouldNotRunATimerThatWasCancelled() {
		final AtomicBoolean ran = new AtomicBoolean();
		final CountDownLatch later = new CountDownLatch(1);

		try (LedgerRuntime runtime = new LedgerRuntime(unique())) {
			final Cancellable cancelled = runtime.schedule(() -> ran.set(true), 100);

			cancelled.cancel();
			runtime.schedule(later::countDown, 300);
			assertThat(later.await(STOP_MILLIS, TimeUnit.MILLISECONDS), is(true));
		}

		assertThat(ran.get(), is(false));
	}

	@Example
	public void shouldHaveBothThreadsAsDaemonsBeforeAnythingIsGivenToThem() {
		final String name = unique();

		try (LedgerRuntime runtime = new LedgerRuntime(name)) {
			assertThat(threadsNamed(name + "-events"), hasSize(1));
			assertThat(threadsNamed(name + "-timers"), hasSize(1));
			assertThat(threadsNamed(name + "-events").get(0).isDaemon(), is(true));
			assertThat(threadsNamed(name + "-timers").get(0).isDaemon(), is(true));
		}
	}

	/* A test container names the threads of a node by what they inherit from the thread that makes them, so the
	   threads belong to whoever builds the runtime and not to whoever first gives them something to do */
	@Example
	@SneakyThrows
	public void shouldGiveBothThreadsWhatTheBuildingThreadHoldsWhoeverUsesThemFirst() {
		final ExecutorService outsider = Executors.newSingleThreadExecutor();
		final AtomicReference<String> onEvents = new AtomicReference<>();
		final AtomicReference<String> onTimers = new AtomicReference<>();
		final CountDownLatch both = new CountDownLatch(2);

		from(outsider, () -> CONTEXT.get());
		CONTEXT.set("the node that built it");

		try (LedgerRuntime runtime = new LedgerRuntime(unique())) {
			from(outsider, () -> {
				runtime.execute(() -> {
					onEvents.set(CONTEXT.get());
					both.countDown();
				});
				runtime.schedule(() -> {
					onTimers.set(CONTEXT.get());
					both.countDown();
				}, 1);
				return null;
			});

			assertThat(both.await(STOP_MILLIS, TimeUnit.MILLISECONDS), is(true));
		} finally {
			CONTEXT.remove();
			outsider.shutdownNow();
		}

		assertThat(onEvents.get(), equalTo("the node that built it"));
		assertThat(onTimers.get(), equalTo("the node that built it"));
	}

	@Example
	public void shouldEndBothThreadsWhenClosedAndSayWhenTheyHave() {
		final String name = unique();
		final LedgerRuntime runtime = new LedgerRuntime(name);

		runtime.close();

		assertThat(runtime.awaitStopped(STOP_MILLIS), is(true));
		assertThat(threadsNamed(name + "-events"), hasSize(0));
		assertThat(threadsNamed(name + "-timers"), hasSize(0));
	}

	@Example
	public void shouldBeClosedAgainWithoutHarm() {
		final LedgerRuntime runtime = new LedgerRuntime(unique());

		runtime.close();
		runtime.close();

		assertThat(runtime.awaitStopped(STOP_MILLIS), is(true));
	}

	@Example
	public void shouldTakeNoMoreWorkOnceClosed() {
		final LedgerRuntime runtime = new LedgerRuntime(unique());

		runtime.close();
		runtime.awaitStopped(STOP_MILLIS);

		assertThrows(RejectedExecutionException.class, () -> runtime.execute(() -> { }));
		assertThrows(RejectedExecutionException.class, () -> runtime.schedule(() -> { }, 1));
	}

	@Example
	@SneakyThrows
	public void shouldSayItHasNotEndedWhileATaskIsStillRunning() {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final LedgerRuntime runtime = new LedgerRuntime(unique());

		runtime.execute(() -> {
			started.countDown();
			awaitQuietly(release);
		});
		started.await(STOP_MILLIS, TimeUnit.MILLISECONDS);
		runtime.close();

		assertThat(runtime.awaitStopped(100), is(false));

		release.countDown();
		assertThat(runtime.awaitStopped(STOP_MILLIS), is(true));
	}

	@Example
	@SneakyThrows
	public void shouldLetTheTaskThatIsRunningFinishWhenClosed() {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean finished = new AtomicBoolean();
		final LedgerRuntime runtime = new LedgerRuntime(unique());

		runtime.execute(() -> {
			started.countDown();
			awaitQuietly(release);
			finished.set(true);
		});
		started.await(STOP_MILLIS, TimeUnit.MILLISECONDS);
		runtime.close();
		release.countDown();

		assertThat(runtime.awaitStopped(STOP_MILLIS), is(true));
		assertThat(finished.get(), is(true));
	}

	private static void awaitQuietly(final CountDownLatch latch) {
		try {
			latch.await(STOP_MILLIS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
