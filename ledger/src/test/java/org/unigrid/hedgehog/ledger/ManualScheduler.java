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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/* Keeps what it is asked to schedule and runs a task only when a test says so, whatever the delay was. A timer that
   was cancelled or has run does nothing when it is fired. With a clock it also knows when each timer is due, and
   advanceBy() lets the time pass, running what falls due in the order it falls due. A delay can be named to run at
   once, for the waits that a test is not about. */
final class ManualScheduler implements Scheduler {
	final class Timer implements Cancellable {
		private final Runnable task;
		private final long delayMillis;
		private final long due;
		private boolean cancelled;
		private boolean fired;

		private Timer(Runnable task, long delayMillis, long due) {
			this.task = task;
			this.delayMillis = delayMillis;
			this.due = due;
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
	private final Set<Long> atOnce = new HashSet<>();
	private final AtomicLong clock;
	private RuntimeException refusal;
	private LongConsumer listener = delay -> { };

	ManualScheduler() {
		this(null);
	}

	ManualScheduler(AtomicLong clock) {
		this.clock = clock;
	}

	/* From now on it refuses what it is asked to schedule, as a scheduler that has been shut down does */
	void refuse(RuntimeException reason) {
		refusal = reason;
	}

	/* Exactly these delays run the moment they are asked for; the ones that were named before are forgotten */
	void runAtOnce(long... delays) {
		atOnce.clear();

		for (final long delay : delays) {
			atOnce.add(delay);
		}
	}

	/* Told the delay of every timer that is asked for, before it is kept */
	void listen(LongConsumer what) {
		listener = what;
	}

	@Override
	public Timer schedule(Runnable task, long delayMillis) {
		if (refusal != null) {
			throw refusal;
		}

		listener.accept(delayMillis);

		final Timer timer = new Timer(task, delayMillis, clock == null ? 0 : clock.get() + delayMillis);

		scheduled.add(timer);

		if (atOnce.contains(delayMillis)) {
			timer.fire();
		}

		return timer;
	}

	/* Lets the time pass: the clock stands at the due time of each timer when it runs, and at the end at the end */
	void advanceBy(long millis) {
		final long end = clock.get() + millis;

		for (Timer next = nextDue(end); next != null; next = nextDue(end)) {
			clock.set(Math.max(clock.get(), next.due));
			next.fire();
		}

		clock.set(Math.max(clock.get(), end));
	}

	private Timer nextDue(long end) {
		return pending().stream().filter(timer -> timer.due <= end).min(Comparator.comparingLong(timer -> timer.due))
			.orElse(null);
	}

	List<Timer> scheduled() {
		return List.copyOf(scheduled);
	}

	List<Timer> pending() {
		return scheduled.stream().filter(Timer::pending).toList();
	}
}
