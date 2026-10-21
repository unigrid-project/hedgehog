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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.ledger.RecordingNetwork.RecordingPeer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

/* A transaction handed to the driver is always answered, whatever becomes of the executor it runs on: a caller holds
   a place for as long as it waits, so a future that nothing completes holds it for ever. The executor is a real one
   with a thread of its own, as the node has. */
public class LedgerDriverShutdownTest {
	private static final long WITHIN_SECONDS = 2;

	private static ThreadPoolExecutor events() {
		return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
			final Thread thread = new Thread(task, "ledger-events");

			thread.setDaemon(true);
			return thread;
		});
	}

	private static DriverRig started(final ThreadPoolExecutor events) throws Exception {
		final DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), DriverRig.STRICT, events::execute).started();

		events.submit(() -> { }).get(WITHIN_SECONDS, TimeUnit.SECONDS);
		return rig;
	}

	/* Holds the one thread of the executor, so that what is given meanwhile queues up behind it */
	private static CountDownLatch occupy(final ThreadPoolExecutor events) {
		final CountDownLatch gate = new CountDownLatch(1);

		events.execute(() -> {
			try {
				gate.await();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});

		return gate;
	}

	@Example
	public void shouldAnswerEverySubmitThatWaitsWhenTheExecutorIsShutDownAndFinishesWhatItHas() throws Exception {
		final ThreadPoolExecutor events = events();

		try (DriverRig rig = started(events)) {
			final CountDownLatch gate = occupy(events);
			final List<CompletableFuture<Optional<String>>> waiting = IntStream.rangeClosed(1, 5)
				.mapToObj(n -> rig.driver.submit(mint(n, 500))).toList();

			events.shutdown();
			gate.countDown();

			for (final CompletableFuture<Optional<String>> answer : waiting) {
				answer.get(WITHIN_SECONDS, TimeUnit.SECONDS);
			}
		}
	}

	@Example
	public void shouldAnswerASubmitThatWaitsBehindTheEndOfTheDriverThatItIsNotRunning() throws Exception {
		final ThreadPoolExecutor events = events();

		try (DriverRig rig = started(events)) {
			final CountDownLatch gate = occupy(events);
			final CompletableFuture<Optional<String>> before = rig.driver.submit(mint(1, 500));

			rig.driver.close();

			final CompletableFuture<Optional<String>> behind = rig.driver.submit(mint(2, 500));

			gate.countDown();
			before.get(WITHIN_SECONDS, TimeUnit.SECONDS);

			assertThat(behind.get(WITHIN_SECONDS, TimeUnit.SECONDS), equalTo(Optional.of(LedgerDriver.NOT_RUNNING)));
		}
	}

	@Example
	public void shouldAnswerASubmitGivenWhileTheLastRunOfAnExecutorThatIsShutDownIsEnding() throws Exception {
		final ThreadPoolExecutor events = events();

		try (DriverRig rig = started(events)) {
			final RecordingPeer asker = rig.network.connect("A");
			final CompletableFuture<CompletableFuture<Optional<String>>> late = new CompletableFuture<>();
			final CountDownLatch gate = occupy(events);

			rig.driver.onBlockRequest(asker, new BlockRequest(1, 1), DriverRig.FRAME).thenRun(() -> {
				events.shutdown();
				late.complete(rig.driver.submit(mint(1, 500)));
			});
			gate.countDown();

			late.get(WITHIN_SECONDS, TimeUnit.SECONDS).get(WITHIN_SECONDS, TimeUnit.SECONDS);
		}
	}

	@Example
	public void shouldTellACallerThatComesAfterTheExecutorWasShutDownThatItIsNotRunning() throws Exception {
		final ThreadPoolExecutor events = events();

		try (DriverRig rig = started(events)) {
			events.shutdown();
			events.awaitTermination(WITHIN_SECONDS, TimeUnit.SECONDS);

			assertThrows(RejectedExecutionException.class, () -> rig.driver.submit(mint(1, 500)));
		}
	}
}
