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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.unigrid.hedgehog.ledger.Scheduler;
import org.unigrid.hedgehog.ledger.Serial;

/* The threads a driver runs on: one that takes its events one at a time, and one that waits for its timers. They are
   made by the thread that starts the driver, so they belong to the same node as it. */
final class LedgerRuntime implements Serial, Scheduler, AutoCloseable {
	private final ExecutorService events;
	private final ScheduledExecutorService timers;

	LedgerRuntime(final String name) {
		events = Executors.newSingleThreadExecutor(named(name + "-events"));
		timers = Executors.newSingleThreadScheduledExecutor(named(name + "-timers"));
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

	@Override
	public void close() {
		timers.shutdownNow();
		events.shutdown();
	}
}
