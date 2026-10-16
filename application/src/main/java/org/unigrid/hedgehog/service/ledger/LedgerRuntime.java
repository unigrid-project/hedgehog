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

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.unigrid.hedgehog.ledger.Scheduler;
import org.unigrid.hedgehog.ledger.Serial;

/* The threads a driver runs on: one that takes its events one at a time, and one that waits for its timers. Both are
   started here, by the thread that builds the runtime, and not by whichever thread first gives them work: a test
   container names the threads of its node by what they inherit from the thread that made them. */
final class LedgerRuntime implements Serial, Scheduler, AutoCloseable {
	private final ThreadPoolExecutor events;
	private final ScheduledThreadPoolExecutor timers;

	LedgerRuntime(final String name) {
		events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
			named(name + "-events"));
		timers = new ScheduledThreadPoolExecutor(1, named(name + "-timers"));
		events.prestartAllCoreThreads();
		timers.prestartAllCoreThreads();
	}

	private static ThreadFactory named(final String name) {
		return task -> {
			final Thread thread = new Thread(task, name);

			thread.setDaemon(true);
			return thread;
		};
	}

	@Override
	public void execute(final Runnable task) {
		events.execute(task);
	}

	@Override
	public Cancellable schedule(final Runnable task, final long delayMillis) {
		final ScheduledFuture<?> scheduled = timers.schedule(task, delayMillis, TimeUnit.MILLISECONDS);

		return () -> scheduled.cancel(false);
	}

	/* A task that is running is not interrupted on the events thread: the driver closes its node first, and what the
	   node has begun it finishes. Timers that are waiting are dropped. */
	@Override
	public void close() {
		timers.shutdownNow();
		events.shutdown();
	}

	/* Whether both threads have ended within the time. It must not be asked of one of them. */
	boolean awaitStopped(final long timeoutMillis) {
		try {
			return events.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)
				&& timers.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
