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

/* Keeps what it is asked to schedule and runs a task only when a test says so, whatever the delay was. A timer that
   was cancelled or has run does nothing when it is fired. */
final class ManualScheduler implements Scheduler {
	final class Timer implements Cancellable {
		private final Runnable task;
		private final long delayMillis;
		private boolean cancelled;
		private boolean fired;

		private Timer(Runnable task, long delayMillis) {
			this.task = task;
			this.delayMillis = delayMillis;
		}

		long delayMillis() {
			return delayMillis;
		}

		boolean cancelled() {
			return cancelled;
		}

		boolean pending() {
			return !cancelled && !fired;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

		void fire() {
			if (pending()) {
				fired = true;
				task.run();
			}
		}
	}

	private final List<Timer> scheduled = new ArrayList<>();
	private RuntimeException refusal;

	/* From now on it refuses what it is asked to schedule, as a scheduler that has been shut down does */
	void refuse(RuntimeException reason) {
		refusal = reason;
	}

	@Override
	public Timer schedule(Runnable task, long delayMillis) {
		if (refusal != null) {
			throw refusal;
		}

		final Timer timer = new Timer(task, delayMillis);

		scheduled.add(timer);
		return timer;
	}

	List<Timer> scheduled() {
		return List.copyOf(scheduled);
	}

	List<Timer> pending() {
		return scheduled.stream().filter(Timer::pending).toList();
	}
}
