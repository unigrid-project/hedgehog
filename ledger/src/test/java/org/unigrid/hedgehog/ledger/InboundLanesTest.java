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

package org.unigrid.hedgehog.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

/* The control lane promises that a task it was given runs, or that the caller is told it will not: a task that was
   refused must not run later, and one that the executor can no longer be asked to run is run by the run that ends */
public class InboundLanesTest {
	private final AtomicBoolean refusing = new AtomicBoolean();
	private final QueuedSerial queue = new QueuedSerial();
	private final List<String> ran = new ArrayList<>();
	private Runnable endOfRun = () -> { };
	private Runnable atRefusal = () -> { };
	private final InboundLanes lanes = new InboundLanes(this::serial, new PeerLimiter(() -> 0, Policy.DEFAULT),
		new QueueBudget(10, 10, 10_000, 10_000), kind -> true, Runnable::run, () -> endOfRun.run());

	private void serial(final Runnable task) {
		if (refusing.get()) {
			atRefusal.run();
			throw new RejectedExecutionException("The executor takes no more tasks");
		}

		queue.execute(task);
	}

	/* A worker thread that a task throws out of ends, and with it the run */
	private void drainAsAWorkerDoes() {
		try {
			queue.drain();
		} catch (RejectedExecutionException ex) {
			assertThat(refusing.get(), equalTo(true));
		}
	}

	@Example
	public void shouldNotRunATaskLaterThatTheCallerWasToldWasRefused() {
		refusing.set(true);
		assertThrows(RejectedExecutionException.class, () -> lanes.control(() -> ran.add("refused")));
		refusing.set(false);
		lanes.control(() -> ran.add("taken"));
		queue.drain();

		assertThat(ran, equalTo(List.of("taken")));
	}

	@Example
	public void shouldRunWhatIsLeftInTheControlLaneWhenTheExecutorRefusesTheRunThatWouldHaveTakenIt() {
		endOfRun = () -> {
			refusing.set(true);
			lanes.control(() -> ran.add("late"));
		};
		lanes.control(() -> ran.add("first"));
		drainAsAWorkerDoes();

		assertThat(ran, equalTo(List.of("first", "late")));
	}

	/* The thread whose run is refused still holds the post while it gives up, so a task that another thread adds
	   meanwhile finds the run posted and waits for it: it is the first thread that has to see to that task */
	@Example
	public void shouldNotLeaveATaskThatMetTheRefusalOfAnotherThreadWhereNothingRunsIt() throws Exception {
		final CountDownLatch executorRefusing = new CountDownLatch(1);
		final CountDownLatch otherAdded = new CountDownLatch(1);
		final ExecutorService first = Executors.newSingleThreadExecutor();

		atRefusal = () -> {
			executorRefusing.countDown();
			awaitUninterruptibly(otherAdded);
		};
		refusing.set(true);

		final Future<?> refused = first.submit(() -> lanes.control(() -> ran.add("first")));

		executorRefusing.await();
		lanes.control(() -> ran.add("second"));
		otherAdded.countDown();

		assertThat(assertThrows(ExecutionException.class, refused::get).getCause().getClass(),
			equalTo(RejectedExecutionException.class));
		first.shutdown();

		assertThat(ran, equalTo(List.of("second")));
	}

	private static void awaitUninterruptibly(final CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
