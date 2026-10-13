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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.service.chain.StreamBudgets.Kind;

public class StreamBudgetsTest {
	private static final long SECOND = 1000;
	private static final int STREAMS = 10_000;
	private static final int THREADS = 8;
	private static final int ROUNDS = 50;
	private static final int TIMEOUT_SECONDS = 30;

	private final AtomicLong clock = new AtomicLong(5 * SECOND);
	private final StreamBudgets budgets = new StreamBudgets(clock::get);

	/* Garbage to collect, kept in a field so that the allocation is not optimised away */
	private volatile byte[] pressure;

	@Property(tries = 50)
	public void admitsNoMoreThanItsLimitWithinOneSecond(@ForAll @Size(min = 1, max = 200) List<@IntRange(min = 1,
		max = 300) Integer> requests) {

		final EmbeddedChannel stream = new EmbeddedChannel();
		final int admitted = requests.stream().filter(units -> budgets.take(stream, Kind.RANGES, units))
			.mapToInt(Integer::intValue).sum();

		assertThat(admitted, lessThanOrEqualTo(Kind.RANGES.getPerSecond()));
	}

	@Example
	public void admitsARequestThatFillsTheBudgetExactlyAndNothingMoreUntilTheNextSecond() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.addAndGet(SECOND - 1);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.addAndGet(1);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
	}

	@Example
	public void neverAdmitsARequestLargerThanTheBudget() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(stream, Kind.HEADS, Kind.HEADS.getPerSecond() + 1), is(false));
		assertThat(budgets.take(stream, Kind.HEADS, 1), is(true));
	}

	@Example
	public void keepsEveryStreamAndEveryKindToItsOwnBudget() {
		final EmbeddedChannel busy = new EmbeddedChannel();
		final EmbeddedChannel quiet = new EmbeddedChannel();

		assertThat(budgets.take(busy, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));

		assertThat(budgets.take(busy, Kind.JOINS, 1), is(false));
		assertThat(budgets.take(busy, Kind.ENTRIES, 1), is(true));
		assertThat(budgets.take(quiet, Kind.JOINS, 1), is(true));
	}

	@Example
	public void admitsEveryKindUpToItsLimitExactly() {
		for (final Kind kind : Kind.values()) {
			final EmbeddedChannel stream = new EmbeddedChannel();
			final EmbeddedChannel greedy = new EmbeddedChannel();

			assertThat(budgets.take(stream, kind, kind.getPerSecond() - 1), is(true));
			assertThat(budgets.take(stream, kind, 1), is(true));
			assertThat(budgets.take(stream, kind, 1), is(false));

			assertThat(budgets.take(greedy, kind, kind.getPerSecond() + 1), is(false));
			assertThat(budgets.take(greedy, kind, kind.getPerSecond()), is(true));
		}
	}

	@Example
	public void givesEveryKindABudgetOfItsOwn() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		for (final Kind kind : Kind.values()) {
			assertThat(budgets.take(stream, kind, kind.getPerSecond()), is(true));
		}

		for (final Kind kind : Kind.values()) {
			assertThat(budgets.take(stream, kind, 1), is(false));
		}
	}

	@Example
	public void opensAWindowWithTheFirstRequestAndClosesItAWholeSecondLater() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		clock.set(5 * SECOND + 500);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));

		clock.set(6 * SECOND + 499);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.set(6 * SECOND + 500);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));
	}

	@Example
	public void givesNoSecondBudgetToRequestsOnBothSidesOfAWholeSecond() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		clock.set(6 * SECOND - 1);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));

		clock.set(6 * SECOND);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));
	}

	@Example
	public void handsOutNothingWhenTheClockStepsBack() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond() - 3), is(true));

		clock.addAndGet(-SECOND);
		assertThat(budgets.take(stream, Kind.JOINS, 3), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.addAndGet(SECOND - 1);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.addAndGet(1);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
	}

	@Example
	public void handsOutNothingWhenTheClockGoesBackAndForthAroundAWholeSecond() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		clock.set(6 * SECOND);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));

		for (int step = 0; step < 10; step++) {
			clock.set(6 * SECOND - 1);
			assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

			clock.set(6 * SECOND);
			assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));
		}

		clock.set(7 * SECOND - 2);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.set(7 * SECOND - 1);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(true));
	}

	@Example
	public void keepsAStreamWaitingNoMoreThanASecondWhenTheClockStepsFarBack() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));

		clock.addAndGet(-3_600 * SECOND);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.addAndGet(SECOND - 1);
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));

		clock.addAndGet(1);
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
	}

	@Example
	public void admitsNoNegativeCountAndHandsNothingBackForIt() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(stream, Kind.JOINS, -1), is(false));
		assertThat(budgets.take(stream, Kind.JOINS, Integer.MIN_VALUE), is(false));
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));
	}

	@Example
	public void admitsACountOfNothingWithoutChargingForIt() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(stream, Kind.JOINS, 0), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, 0), is(true));
		assertThat(budgets.take(stream, Kind.JOINS, 1), is(false));
	}

	@Example
	public void admitsNoCountThatWouldOverflowWhatWasUsed() {
		for (final Kind kind : Kind.values()) {
			final EmbeddedChannel stream = new EmbeddedChannel();

			assertThat(budgets.take(stream, kind, 1), is(true));
			assertThat(budgets.take(stream, kind, Integer.MAX_VALUE), is(false));
			assertThat(budgets.take(stream, kind, kind.getPerSecond() - 1), is(true));
			assertThat(budgets.take(stream, kind, 1), is(false));
		}
	}

	@Example
	public void takesNothingForNoStream() {
		final EmbeddedChannel stream = new EmbeddedChannel();

		assertThat(budgets.take(null, Kind.JOINS, 1), is(false));
		assertThat(budgets.take(null, Kind.JOINS, 0), is(false));
		assertThat(budgets.take(stream, Kind.JOINS, Kind.JOINS.getPerSecond()), is(true));
	}

	@Example
	public void holdsOnToNoStreamThatIsGone() {
		final List<WeakReference<EmbeddedChannel>> gone = IntStream.range(0, STREAMS).mapToObj(number -> {
			final EmbeddedChannel stream = new EmbeddedChannel();

			assertThat(budgets.take(stream, Kind.ENTRIES, 1), is(true));
			stream.close();
			return new WeakReference<>(stream);
		}).toList();

		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (gone.stream().anyMatch(stream -> stream.get() != null) && System.nanoTime() < deadline) {
			pressure = new byte[1 << 20];
			System.gc();
		}

		assertThat(gone.stream().filter(stream -> stream.get() != null).count(), is(0L));
	}

	@Example @SneakyThrows
	public void admitsExactlyTheLimitToThreadsThatTakeFromOneStreamAtOnce() {
		final ExecutorService threads = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final EmbeddedChannel stream = new EmbeddedChannel();
				final CountDownLatch go = new CountDownLatch(1);
				final List<Future<Long>> taken = new ArrayList<>();

				for (int thread = 0; thread < THREADS; thread++) {
					taken.add(threads.submit(() -> {
						assertThat(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
						return IntStream.range(0, 2 * Kind.ENTRIES.getPerSecond() / THREADS)
							.filter(attempt -> budgets.take(stream, Kind.ENTRIES, 1)).count();
					}));
				}

				go.countDown();

				long total = 0;

				for (final Future<Long> share : taken) {
					total += share.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}

				assertThat(total, is((long) Kind.ENTRIES.getPerSecond()));
			}
		} finally {
			threads.shutdownNow();
		}
	}
}