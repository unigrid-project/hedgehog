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

package org.unigrid.hedgehog.model.cdi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Data;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.apache.commons.configuration2.sync.LockMode;
import org.awaitility.Awaitility;
import static org.awaitility.Awaitility.*;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.function.VoidFunctionE;

@WeldSetup({ ProtectedInterceptorTest.LockModeProtected.class })
public class ProtectedInterceptorTest extends BaseMockedWeldTest {
	@Data
	@ApplicationScoped
	public static class LockModeProtected {
		@SneakyThrows @Protected @Lock(LockMode.READ)
		void readProtect(VoidFunctionE function) {
			function.apply();
		}

		@SneakyThrows @Protected @Lock(LockMode.WRITE)
		void writeProtect(VoidFunctionE function) {
			function.apply();
		}
	}

	private static final long TIMEOUT_SECONDS = 10;

	@Inject
	private LockModeProtected lockModeProtected;

	@BeforeContainer
	private static void before() {
		Awaitility.setDefaultPollInterval(5, TimeUnit.MILLISECONDS);
		Awaitility.setDefaultPollDelay(5, TimeUnit.MILLISECONDS);
	}

	/* A body that runs on a thread of its own, with the failure of it handed back through a future */
	private record Background(Thread thread, CompletableFuture<Void> done) { }

	private static Background background(VoidFunctionE<Exception> body) {
		final CompletableFuture<Void> done = new CompletableFuture<>();
		final Thread thread = new Thread(() -> {
			try {
				body.apply();
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		});

		thread.start();
		return new Background(thread, done);
	}

	private static void reach(CountDownLatch latch) throws InterruptedException {
		if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			throw new IllegalStateException("The other thread did not get there in time");
		}
	}

	/* A thread that waits for the lock is parked, which is as near as anyone can get to knowing that it waits */
	private static void awaitWaiting(Thread thread) {
		await().until(() -> EnumSet.of(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED)
			.contains(thread.getState()));
	}

	@Example
	public void shoulBeAbleToWriteProtectMethods() throws Exception {
		final AtomicInteger counter = new AtomicInteger();
		final CountDownLatch holding = new CountDownLatch(1);
		final CountDownLatch contenderWaits = new CountDownLatch(1);
		final CompletableFuture<Integer> seenWhileHolding = new CompletableFuture<>();

		final Background holder = background(() -> {
			lockModeProtected.writeProtect(() -> {
				counter.incrementAndGet();
				holding.countDown();
				reach(contenderWaits);
				seenWhileHolding.complete(counter.get());
				counter.incrementAndGet();
			});
		});

		/* Make sure the above thread has started and is locking */
		reach(holding);

		final Background contender = background(() -> {
			lockModeProtected.writeProtect(() -> {
				counter.incrementAndGet();
			});
		});

		awaitWaiting(contender.thread());
		contenderWaits.countDown();
		await().untilAtomic(counter, is(3));

		holder.done().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		contender.done().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		/* 1, because we should not have been able to lock from the other thread */
		assertThat(seenWhileHolding.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(1));
	}

	@Example
	public void shoulBeAbleToReadProtectMethods() throws Exception {
		final AtomicInteger counter = new AtomicInteger();
		final CountDownLatch holding = new CountDownLatch(1);
		final CountDownLatch contenderWaits = new CountDownLatch(1);
		final CountDownLatch contenderReads = new CountDownLatch(1);
		final CountDownLatch holderReads = new CountDownLatch(1);
		final CompletableFuture<Integer> seenWhileWriting = new CompletableFuture<>();
		final CompletableFuture<Integer> seenWhileReading = new CompletableFuture<>();

		final Background holder = background(() -> {
			lockModeProtected.writeProtect(() -> {
				counter.incrementAndGet();
				holding.countDown();
				reach(contenderWaits);
				seenWhileWriting.complete(counter.get());
				counter.incrementAndGet();
			});

			lockModeProtected.readProtect(() -> {
				holderReads.countDown();
				reach(contenderReads);
				seenWhileReading.complete(counter.get());
				counter.incrementAndGet();
			});
		});

		/* Make sure the above thread has started and is locking */
		reach(holding);

		/* Holds its read lock until the other thread has passed the read lock as well */
		final Background contender = background(() -> {
			lockModeProtected.readProtect(() -> {
				counter.incrementAndGet();
				contenderReads.countDown();
				reach(holderReads);
			});
		});

		awaitWaiting(contender.thread());
		contenderWaits.countDown();
		await().untilAtomic(counter, is(4));

		holder.done().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		contender.done().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

		/* 1, because we should not have been able to lock from the other thread */
		assertThat(seenWhileWriting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(1));

		/* 3, because the read lock below should have passed */
		assertThat(seenWhileReading.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(3));
	}
}
