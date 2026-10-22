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

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.PeerLimiter.Rate;

public class PeerLimiterTest {
	private static final long START = 1_000_000L;
	private static final int NEVER = Integer.MAX_VALUE;
	private static final Rate UNLIMITED = new Rate(Integer.MAX_VALUE, Integer.MAX_VALUE);

	private static Map<Kind, Rate> same(Rate rate) {
		return Arrays.stream(Kind.values()).collect(Collectors.toMap(Function.identity(), kind -> rate));
	}

	/* No decay and no limit shared by all peers: what a test does not look at stays out of its way */
	private static Policy uniform(int capacity, int refill, int scoreLimit, long penaltyMillis, int maxPeers) {
		return new Policy(same(new Rate(capacity, refill)), same(UNLIMITED), scoreLimit, 0, penaltyMillis, maxPeers);
	}

	private static Policy shared(int capacity, int refill) {
		return new Policy(same(UNLIMITED), same(new Rate(capacity, refill)), 100, 0, 60_000, 100);
	}

	private static Policy decaying(int perMinute) {
		return new Policy(same(UNLIMITED), same(UNLIMITED), 100, perMinute, 60_000, 100);
	}

	/* Tokens only, no penalties: the score limit is out of reach of every test that does not look at it */
	private static Policy buckets(int capacity, int refill) {
		return uniform(capacity, refill, NEVER, 60_000, 100);
	}

	private static int granted(PeerLimiter limiter, String peer, Kind kind, int attempts) {
		return (int) IntStream.range(0, attempts).filter(attempt -> limiter.allow(peer, kind)).count();
	}

	private static void penalise(PeerLimiter limiter, String peer) {
		limiter.misbehaved(peer, 100);
	}

	@Example
	public void shouldBanAPeerAtOnceForTheWholePenaltyWhateverItsScore() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 100));

		limiter.ban("a");

		assertThat(limiter.isPenalised("a"), is(true));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		assertThat(limiter.allow("b", Kind.TRANSACTION), is(true));

		clock.addAndGet(59_999);
		assertThat(limiter.isPenalised("a"), is(true));

		clock.addAndGet(1);
		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
	}

	@Example
	public void shouldNotPushOutThePenaltyOfAPeerThatIsBannedAgain() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 100));

		limiter.ban("a");
		clock.addAndGet(30_000);
		limiter.ban("a");
		clock.addAndGet(30_000);

		assertThat(limiter.isPenalised("a"), is(false));
	}

	@Example
	public void shouldPassABurstOfTheCapacityAndRefuseTheNext() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, buckets(5, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 5), equalTo(5));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
	}

	@Example
	public void shouldRefillWithTheClock() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 200), equalTo(200));
		clock.addAndGet(500);
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 60), equalTo(25));
	}

	@Example
	public void shouldRefillInWholeMillisecondsWithoutLosingAny() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(10, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 10), equalTo(10));
		clock.addAndGet(999);
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		clock.addAndGet(1);
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
	}

	@Example
	public void shouldNeverHoldMoreThanTheCapacityHoweverLongThePeerWaits() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(7, 3));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 7), equalTo(7));
		clock.addAndGet(Long.MAX_VALUE / 2);
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 20), equalTo(7));
	}

	@Example
	public void shouldNotRefillPastTheCapacityWhenOnlyPartOfItWasTaken() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(5, 1));

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
		clock.addAndGet(3000);
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 10), equalTo(5));
	}

	@Example
	public void shouldKeepTheKindsApart() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, buckets(2, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 5), equalTo(2));

		for (final Kind other : Arrays.stream(Kind.values()).filter(kind -> kind != Kind.TRANSACTION).toList()) {
			assertThat(granted(limiter, "a", other, 5), equalTo(2));
		}
	}

	@Example
	public void shouldKeepThePeersApart() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, buckets(2, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 5), equalTo(2));
		assertThat(granted(limiter, "b", Kind.TRANSACTION, 5), equalTo(2));
	}

	@Example
	public void shouldPenaliseAPeerWhoseMisbehaviourReachesTheLimit() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, Policy.DEFAULT);

		limiter.misbehaved("a", 60);
		limiter.misbehaved("a", 39);
		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.allow("a", Kind.STATUS), is(true));
		limiter.misbehaved("a", 1);
		assertThat(limiter.isPenalised("a"), is(true));
		assertThat(limiter.isPenalised("b"), is(false));
	}

	@Example
	public void shouldRefuseAPenalisedPeerEveryKindUntilThePenaltyEnds() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);

		penalise(limiter, "a");
		clock.addAndGet(59_999);

		assertThat(limiter.isPenalised("a"), is(true));
		Arrays.stream(Kind.values()).forEach(kind -> assertThat(limiter.allow("a", kind), is(false)));
		clock.addAndGet(1);
		assertThat(limiter.isPenalised("a"), is(false));
		Arrays.stream(Kind.values()).forEach(kind -> assertThat(limiter.allow("a", kind), is(true)));
	}

	@Example
	public void shouldStartAgainWithFreshBucketsAndAFreshScore() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(4, 1, 100, 1000, 100));

		assertThat(granted(limiter, "a", Kind.BLOCK_REQUEST, 4), equalTo(4));
		limiter.misbehaved("a", 99);
		penalise(limiter, "a");
		clock.addAndGet(1000);

		assertThat(granted(limiter, "a", Kind.BLOCK_REQUEST, 4), equalTo(4));
		limiter.misbehaved("a", 99);
		assertThat(limiter.isPenalised("a"), is(false));
	}

	@Example
	public void shouldPenaliseAPeerThatRunsOutOfTokensOneHundredTimes() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(1, 1, 100, 60_000, 100));

		assertThat(limiter.allow("a", Kind.CONSENSUS), is(true));
		assertThat(granted(limiter, "a", Kind.CONSENSUS, 99), equalTo(0));
		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.allow("a", Kind.CONSENSUS), is(false));
		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldNotLetMisbehaviourDuringAPenaltyExtendIt() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);

		penalise(limiter, "a");
		clock.addAndGet(30_000);
		penalise(limiter, "a");
		clock.addAndGet(30_000);

		assertThat(limiter.isPenalised("a"), is(false));
	}

	@Example
	public void shouldNotWrapTheScoreOfAHugeWeight() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, Policy.DEFAULT);

		limiter.misbehaved("a", 99);
		limiter.misbehaved("a", Integer.MAX_VALUE);
		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldRefuseAWeightBelowOne() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, Policy.DEFAULT);

		assertThrows(IllegalArgumentException.class, () -> limiter.misbehaved("a", 0));
		assertThrows(IllegalArgumentException.class, () -> limiter.misbehaved("a", -5));
	}

	@Example
	public void shouldTrackNoMorePeersThanTheCap() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(5, 1, 100, 60_000, 50));

		for (int peer = 0; peer < 50 + 1000; peer++) {
			limiter.allow("peer" + peer, Kind.TRANSACTION);
			assertThat(limiter.tracked(), lessThanOrEqualTo(50));
		}

		assertThat(limiter.tracked(), equalTo(50));
	}

	@Example
	public void shouldDropTheLeastRecentlyActivePeerFirst() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(1, 1, NEVER, 60_000, 3));

		limiter.allow("a", Kind.TRANSACTION);
		limiter.allow("b", Kind.TRANSACTION);
		limiter.allow("c", Kind.TRANSACTION);
		limiter.allow("a", Kind.TRANSACTION);
		limiter.allow("d", Kind.TRANSACTION);

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		assertThat(limiter.allow("d", Kind.TRANSACTION), is(false));
		assertThat(limiter.allow("b", Kind.TRANSACTION), is(true));
	}

	/* No flood of new peers frees a penalised one: only the end of the penalty does, or a full map of penalties */
	@Example
	public void shouldKeepAPenalisedPeerUntilItsPenaltyEndsHoweverManyNewPeersCome() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 50));

		penalise(limiter, "bad");

		for (int peer = 0; peer < 50 + 1000; peer++) {
			limiter.allow("peer" + peer, Kind.TRANSACTION);
			assertThat(limiter.isPenalised("bad"), is(true));
		}

		assertThat(limiter.tracked(), equalTo(50 + 1));
		clock.addAndGet(59_999);
		assertThat(limiter.isPenalised("bad"), is(true));
		clock.addAndGet(1);
		assertThat(limiter.isPenalised("bad"), is(false));
	}

	/* What is lost when an active peer is dropped is its score, and it is dropped only for a newer peer */
	@Example
	public void shouldLetAnActivePeerStartAgainWithNoScoreWhenItWasDropped() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(5, 1, 100, 60_000, 2));

		limiter.misbehaved("b", 99);
		limiter.allow("c", Kind.TRANSACTION);
		limiter.allow("d", Kind.TRANSACTION);
		limiter.misbehaved("b", 99);
		assertThat(limiter.isPenalised("b"), is(false));
	}

	/* The map of the penalties, which counts the steps taken when it is walked */
	private static final class WalkCountingMap extends LinkedHashMap<String, Long> {
		private long steps;

		@Override
		public Collection<Long> values() {
			final Collection<Long> real = super.values();

			return new AbstractCollection<>() {
				@Override
				public Iterator<Long> iterator() {
					return counted(real.iterator());
				}

				@Override
				public int size() {
					return real.size();
				}
			};
		}

		@Override
		public Set<String> keySet() {
			final Set<String> real = super.keySet();

			return new AbstractSet<>() {
				@Override
				public Iterator<String> iterator() {
					return counted(real.iterator());
				}

				@Override
				public int size() {
					return real.size();
				}
			};
		}

		private <T> Iterator<T> counted(Iterator<T> real) {
			return new Iterator<>() {
				@Override
				public boolean hasNext() {
					return real.hasNext();
				}

				@Override
				public T next() {
					steps++;
					return real.next();
				}

				@Override
				public void remove() {
					real.remove();
				}
			};
		}
	}

	/* Fresh addresses must cost the limiter a step each, however many penalised peers it holds, and each must be
	   served: filling the table with penalties is not a way to lock the node's other peers out */
	@Example
	public void shouldServeEveryNewPeerAndDoWorkInProportionToTheCallsAfterTenThousandPenalties() {
		final AtomicLong clock = new AtomicLong(START);
		final WalkCountingMap penalties = new WalkCountingMap();
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 20_000), penalties);

		IntStream.range(0, 10_000).forEach(peer -> penalise(limiter, "bad" + peer));
		IntStream.range(0, 100_000)
			.forEach(peer -> assertThat(limiter.allow("fresh" + peer, Kind.TRANSACTION), is(true)));

		assertThat(limiter.isPenalised("bad0"), is(true));
		assertThat(limiter.isPenalised("bad9999"), is(true));
		clock.addAndGet(60_000);
		assertThat(limiter.allow("fresh", Kind.TRANSACTION), is(true));
		assertThat(limiter.isPenalised("bad0"), is(false));
		assertThat(penalties.steps, lessThanOrEqualTo(3L * (10_000 + 100_000 + 4)));
	}

	/* The price of a bounded map: a penalty that finds half the table already penalised frees the oldest one early */
	@Example
	public void shouldFreeTheOldestPenalisedPeerEarlyOnlyWhenAnotherPenaltyFindsTheMapFull() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(5, 1, 100, 60_000, 6));

		List.of("a", "b", "c").forEach(peer -> penalise(limiter, peer));

		assertThat(limiter.isPenalised("a"), is(true));
		assertThat(limiter.tracked(), equalTo(3));

		penalise(limiter, "d");

		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.isPenalised("b"), is(true));
		assertThat(limiter.isPenalised("c"), is(true));
		assertThat(limiter.isPenalised("d"), is(true));
		assertThat(limiter.tracked(), equalTo(3));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
	}

	@Example
	public void shouldTakeNothingFromTheLimitForAllPeersForAPenalisedPeer() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(5, 1));

		penalise(limiter, "bad");
		assertThat(granted(limiter, "bad", Kind.TRANSACTION, 1000), equalTo(0));

		assertThat(IntStream.range(0, 5).filter(peer -> limiter.allow("peer" + peer, Kind.TRANSACTION)).count(),
			equalTo(5L));
	}

	@Example
	public void shouldForgetAPeerWhosePenaltyHasEnded() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 4));

		penalise(limiter, "a");
		clock.addAndGet(30_000);
		penalise(limiter, "b");
		clock.addAndGet(30_000);

		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.isPenalised("b"), is(true));
		assertThat(limiter.tracked(), equalTo(1));
	}

	@Example
	public void shouldCreateNoTokensWhenTheClockStepsBack() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(10, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 10), equalTo(10));
		clock.set(START - 5000);
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		clock.set(0);
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		clock.set(Long.MIN_VALUE);
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
	}

	/* A clock that was set back for good must not freeze the bucket until it passes the old reading again */
	@Example
	public void shouldRefillFromTheNewReadingAfterAStepBack() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(10, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 10), equalTo(10));
		clock.set(0);
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		clock.set(2000);
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 5), equalTo(2));
	}

	@Example
	public void shouldNotLetAStepBackStretchAPenalty() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);

		penalise(limiter, "a");
		clock.set(0);
		assertThat(limiter.isPenalised("a"), is(true));
		clock.set(59_999);
		assertThat(limiter.isPenalised("a"), is(true));
		clock.set(60_000);
		assertThat(limiter.isPenalised("a"), is(false));
	}

	@Example
	public void shouldNotReviveAnEndedPenaltyWhenTheClockStepsBack() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);

		penalise(limiter, "a");
		clock.addAndGet(60_000);
		assertThat(limiter.isPenalised("a"), is(false));
		clock.set(START - 3_600_000);

		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
	}

	@Example
	public void shouldFillTheBucketAcrossTheWholeRangeOfTheClock() {
		final AtomicLong clock = new AtomicLong(Long.MIN_VALUE);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(5, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 8), equalTo(5));
		clock.set(Long.MAX_VALUE);
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 8), equalTo(5));
	}

	@Example
	public void shouldNotLetAPenaltyNearTheEndOfTimeWrapAround() {
		final AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);

		penalise(limiter, "a");
		clock.addAndGet(5);
		assertThat(limiter.isPenalised("a"), is(true));
		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
	}

	@Property(tries = 200)
	public void shouldNeverHoldMoreThanTheCapacityAfterAnyHistoryOfTheClock(@ForAll @Size(max = 30) List<Long> readings,
		@ForAll long last) {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(6, 1000));

		readings.forEach(reading -> {
			clock.set(reading);
			limiter.allow("a", Kind.TRANSACTION);
		});

		clock.set(last);
		limiter.allow("a", Kind.TRANSACTION);
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 20), lessThanOrEqualTo(6));
	}

	@Property(tries = 100)
	public void shouldBeDeterministic(@ForAll long seed) {
		assertThat(replay(seed), equalTo(replay(seed)));
	}

	private static List<Object> replay(long seed) {
		final Random random = new Random(seed);
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get,
			new Policy(same(new Rate(3, 2)), same(new Rate(8, 3)), 50, 30, 5000, 5));
		final List<Object> outcomes = new ArrayList<>();

		for (int step = 0; step < 300; step++) {
			final String peer = "peer" + random.nextInt(12);
			clock.addAndGet(random.nextInt(3000));

			switch (random.nextInt(3)) {
				case 0 -> outcomes.add(limiter.allow(peer, Kind.values()[random.nextInt(Kind.values().length)]));
				case 1 -> limiter.misbehaved(peer, 1 + random.nextInt(40));
				default -> outcomes.add(limiter.isPenalised(peer));
			}

			outcomes.add(limiter.tracked());
		}

		return outcomes;
	}

	@Example
	public void shouldLowerTheScoreWithTime() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, decaying(60));

		limiter.misbehaved("a", 90);
		clock.addAndGet(30_000);
		limiter.misbehaved("a", 30);

		assertThat(limiter.isPenalised("a"), is(false));
		limiter.misbehaved("a", 10);
		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldNeverLowerTheScoreBelowZero() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, decaying(60));

		limiter.misbehaved("a", 50);
		clock.addAndGet(Long.MAX_VALUE / 2);
		limiter.misbehaved("a", 99);

		assertThat(limiter.isPenalised("a"), is(false));
		limiter.misbehaved("a", 1);
		assertThat(limiter.isPenalised("a"), is(true));
	}

	/* A point every 100 ms is what a decay of 600 a minute takes away: it must not wait for whole minutes to count */
	@Example
	public void shouldLowerTheScoreInSmallStepsAndNotInWholeMinutes() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, decaying(600));

		for (int step = 0; step < 1000; step++) {
			limiter.misbehaved("a", 1);
			clock.addAndGet(100);
			assertThat(limiter.isPenalised("a"), is(false));
		}
	}

	@Example
	public void shouldNotLowerTheScoreWhenTheClockStepsBack() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, decaying(60));

		limiter.misbehaved("a", 90);
		clock.addAndGet(-3_600_000);
		limiter.misbehaved("a", 10);

		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldKeepTheScoreWhenThereIsNoDecay() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, decaying(0));

		limiter.misbehaved("a", 90);
		clock.addAndGet(Long.MAX_VALUE / 2);
		limiter.misbehaved("a", 10);

		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldLimitWhatAllPeersTakeTogether() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, shared(5, 1));

		IntStream.range(0, 5).forEach(peer -> assertThat(limiter.allow("peer" + peer, Kind.TRANSACTION), is(true)));
		assertThat(limiter.allow("peer5", Kind.TRANSACTION), is(false));
		assertThat(limiter.allow("peer0", Kind.TRANSACTION), is(false));
		clock.addAndGet(1000);
		assertThat(limiter.allow("peer6", Kind.TRANSACTION), is(true));
		assertThat(limiter.allow("peer7", Kind.TRANSACTION), is(false));
	}

	@Example
	public void shouldKeepTheLimitOfEachKindForAllPeersApart() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(2, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 5), equalTo(2));
		assertThat(granted(limiter, "b", Kind.HEARTBEAT, 5), equalTo(2));
	}

	@Example
	public void shouldNotScoreAPeerForWhatTheLimitForAllPeersRefuses() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(1, 1));

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
		assertThat(granted(limiter, "b", Kind.TRANSACTION, 1000), equalTo(0));
		IntStream.range(0, 1000).forEach(peer -> limiter.allow("peer" + peer, Kind.TRANSACTION));

		assertThat(limiter.isPenalised("b"), is(false));
		assertThat(IntStream.range(0, 1000).filter(peer -> limiter.isPenalised("peer" + peer)).count(), equalTo(0L));
	}

	/* A peer that is over its own limit is a cost to no one but itself, or one peer could empty the bucket of all */
	@Example
	public void shouldTakeNothingFromTheLimitForAllPeersForARefusalOfThePeerItself() {
		final PeerLimiter limiter = new PeerLimiter(() -> START,
			new Policy(same(new Rate(1, 1)), same(new Rate(3, 1)), 100, 0, 60_000, 100));

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
		assertThat(granted(limiter, "a", Kind.TRANSACTION, 50), equalTo(0));
		assertThat(limiter.allow("b", Kind.TRANSACTION), is(true));
		assertThat(limiter.allow("c", Kind.TRANSACTION), is(true));
		assertThat(limiter.allow("d", Kind.TRANSACTION), is(false));
	}

	/* A copy of what the node holds already is dropped without any work, so it is no one's cost but its sender's */
	@Example
	public void shouldTakeACopyFromTheBucketOfItsSenderAndNothingFromTheLimitForAllPeers() {
		final PeerLimiter limiter = new PeerLimiter(() -> START,
			new Policy(same(new Rate(3, 1)), same(new Rate(1, 1)), 100, 0, 60_000, 100));

		assertThat(IntStream.range(0, 3).allMatch(copy -> limiter.allowCopy("a", Kind.CONSENSUS)), is(true));
		assertThat(limiter.allowCopy("a", Kind.CONSENSUS), is(false));
		assertThat(limiter.allow("a", Kind.CONSENSUS), is(false));
		assertThat(limiter.allow("b", Kind.CONSENSUS), is(true));
		assertThat(limiter.allowCopy("c", Kind.CONSENSUS), is(true));
	}

	@Example
	public void shouldPenaliseAPeerWhoseCopiesRunOutOfItsTokensOneHundredTimes() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(1, 1, 100, 60_000, 100));

		assertThat(limiter.allowCopy("a", Kind.CONSENSUS), is(true));
		IntStream.range(0, 99).forEach(copy -> limiter.allowCopy("a", Kind.CONSENSUS));

		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.allowCopy("a", Kind.CONSENSUS), is(false));
		assertThat(limiter.isPenalised("a"), is(true));
		assertThat(limiter.allowCopy("a", Kind.CONSENSUS), is(false));
	}

	/* The reserve is a quarter of what all peers share, here one token of four */
	@Example
	public void shouldLeaveAPeerThatWasVouchedForAReserveThatTheOthersCannotEmpty() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(4, 1));

		limiter.vouch("v");
		IntStream.range(0, 4).forEach(peer -> assertThat(limiter.allow("peer" + peer, Kind.CONSENSUS), is(true)));

		assertThat(limiter.allow("other", Kind.CONSENSUS), is(false));
		assertThat(limiter.allow("v", Kind.CONSENSUS), is(true));
		assertThat(limiter.allow("v", Kind.CONSENSUS), is(false));
		assertThat(limiter.allow("other", Kind.CONSENSUS), is(false));
	}

	@Example
	public void shouldDrawOnTheReserveOnlyOnceWhatAllPeersShareIsGone() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(4, 1));

		limiter.vouch("v");

		assertThat(granted(limiter, "v", Kind.TRANSACTION, 10), equalTo(5));
		assertThat(limiter.allow("other", Kind.TRANSACTION), is(false));
	}

	@Example
	public void shouldEndAVouchAMinuteAfterTheLastOne() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, shared(4, 1));

		limiter.vouch("v");
		clock.addAndGet(30_000);
		limiter.vouch("v");
		clock.addAndGet(59_999);
		IntStream.range(0, 4).forEach(peer -> limiter.allow("a" + peer, Kind.CONSENSUS));

		assertThat(limiter.allow("v", Kind.CONSENSUS), is(true));

		clock.addAndGet(10_000);
		IntStream.range(0, 4).forEach(peer -> limiter.allow("b" + peer, Kind.CONSENSUS));

		assertThat(limiter.allow("v", Kind.CONSENSUS), is(false));
	}

	/* A peer that relays what others signed can be the first to bring it, so a vouch is no proof of honesty: what it
	   takes of the reserve is bounded by what it brought new, two tokens for each item */
	@Example
	public void shouldLetAVouchedPeerTakeNoMoreOfTheReserveThanTwiceWhatItBroughtNew() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(40, 1));

		limiter.vouch("v");

		assertThat(granted(limiter, "v", Kind.CONSENSUS, 100), equalTo(42));

		limiter.vouch("v");

		assertThat(granted(limiter, "v", Kind.CONSENSUS, 100), equalTo(2));
		assertThat(limiter.allow("w", Kind.CONSENSUS), is(false));
	}

	@Example
	public void shouldRefuseANewVouchWhileTheVouchedPeersFillTheirMapAndKeepTheOnesItHolds() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get,
			new Policy(same(UNLIMITED), same(new Rate(4, 1)), 100, 0, 60_000, 4));

		limiter.vouch("a");
		limiter.vouch("b");
		limiter.vouch("c");
		clock.addAndGet(30_000);
		limiter.vouch("a");
		IntStream.range(0, 4).forEach(peer -> limiter.allow("x" + peer, Kind.CONSENSUS));

		assertThat(limiter.allow("c", Kind.CONSENSUS), is(false));
		assertThat(limiter.allow("b", Kind.CONSENSUS), is(true));

		clock.addAndGet(30_000);
		limiter.vouch("c");
		IntStream.range(0, 4).forEach(peer -> limiter.allow("y" + peer, Kind.CONSENSUS));

		assertThat(limiter.allow("c", Kind.CONSENSUS), is(true));
	}

	@Example
	public void shouldForgetTheVouchOfAPeerThatIsPenalised() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get,
			new Policy(same(UNLIMITED), same(new Rate(4, 1)), 100, 0, 1000, 100));

		limiter.vouch("v");
		limiter.ban("v");
		limiter.vouch("v");
		clock.addAndGet(1000);
		IntStream.range(0, 4).forEach(peer -> limiter.allow("peer" + peer, Kind.CONSENSUS));

		assertThat(limiter.isPenalised("v"), is(false));
		assertThat(limiter.allow("v", Kind.CONSENSUS), is(false));
	}

	@Example
	public void shouldLeaveTheReserveToWhatIsPlausible() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, shared(4, 1));

		limiter.vouch("v");

		assertThat(limiter.allow("v", Kind.CONSENSUS, false), is(true));
		assertThat(limiter.allow("v", Kind.CONSENSUS, false), is(false));
	}

	@Example
	public void shouldKeepTheTokenOfAPeerThatTheLimitForAllPeersRefused() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get,
			new Policy(same(new Rate(1, 1)), same(new Rate(1, 1000)), 100, 0, 60_000, 100));

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));
		assertThat(limiter.allow("b", Kind.TRANSACTION), is(false));
		clock.addAndGet(1);
		assertThat(limiter.allow("b", Kind.TRANSACTION), is(true));
	}

	@Example
	public void shouldNotWrapTheTimeItHasSeenWhateverTheClockDoes() {
		final AtomicLong clock = new AtomicLong(0);
		final PeerLimiter limiter = new PeerLimiter(clock::get, buckets(5, 1));

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 5), equalTo(5));
		clock.set(Long.MAX_VALUE);
		limiter.isPenalised("a");
		clock.set(0);
		limiter.isPenalised("a");
		clock.set(Long.MAX_VALUE);

		assertThat(granted(limiter, "a", Kind.TRANSACTION, 8), equalTo(5));
	}

	@Example
	public void shouldRefuseAPolicyThatCannotWork() {
		final Map<Kind, Rate> rates = same(new Rate(1, 1));
		final Map<Kind, Rate> lacking = new EnumMap<>(rates);
		lacking.remove(Kind.STATUS);

		assertThrows(IllegalArgumentException.class, () -> new Rate(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new Rate(1, 0));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, rates, 0, 0, 1, 2));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, rates, 1, -1, 1, 2));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, rates, 1, 0, 0, 2));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, rates, 1, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new Policy(lacking, rates, 1, 0, 1, 2));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, lacking, 1, 0, 1, 2));
		assertThat(new Policy(rates, rates, 1, 0, 1, 2).maxPeers(), equalTo(2));
	}

	@Example
	public void shouldNotFollowAChangeToTheMapsThePolicyWasBuiltFrom() {
		final Map<Kind, Rate> rates = new EnumMap<>(same(new Rate(1, 1)));
		final Map<Kind, Rate> global = new EnumMap<>(same(new Rate(2, 2)));

		final Policy policy = new Policy(rates, global, 1, 0, 1, 2);
		rates.put(Kind.STATUS, new Rate(9, 9));
		global.put(Kind.STATUS, new Rate(9, 9));

		assertThat(policy.rates().get(Kind.STATUS), equalTo(new Rate(1, 1)));
		assertThat(policy.global().get(Kind.STATUS), equalTo(new Rate(2, 2)));
		assertThrows(UnsupportedOperationException.class, () -> policy.rates().put(Kind.STATUS, new Rate(9, 9)));
		assertThrows(UnsupportedOperationException.class, () -> policy.global().put(Kind.STATUS, new Rate(9, 9)));
	}

	@Example
	public void shouldDefaultToTheDocumentedLimits() {
		final Map<Kind, Rate> rates = Policy.DEFAULT.rates();
		final Map<Kind, Rate> global = Policy.DEFAULT.global();

		assertThat(rates.get(Kind.TRANSACTION), equalTo(new Rate(200, 50)));
		assertThat(rates.get(Kind.HEARTBEAT), equalTo(new Rate(20, 2)));
		assertThat(rates.get(Kind.CONSENSUS), equalTo(new Rate(2000, 500)));
		assertThat(rates.get(Kind.STATUS), equalTo(new Rate(10, 1)));
		assertThat(rates.get(Kind.BLOCK_REQUEST), equalTo(new Rate(40, 10)));
		assertThat(global.get(Kind.TRANSACTION), equalTo(new Rate(2000, 500)));
		assertThat(global.get(Kind.HEARTBEAT), equalTo(new Rate(500, 20)));
		assertThat(global.get(Kind.CONSENSUS), equalTo(new Rate(20_000, 5000)));
		assertThat(global.get(Kind.STATUS), equalTo(new Rate(200, 20)));
		assertThat(global.get(Kind.BLOCK_REQUEST), equalTo(new Rate(200, 100)));
		assertThat(Policy.DEFAULT.scoreLimit(), equalTo(100));
		assertThat(Policy.DEFAULT.scoreDecayPerMinute(), equalTo(10));
		assertThat(Policy.DEFAULT.penaltyMillis(), equalTo(60_000L));
		assertThat(Policy.DEFAULT.maxPeers(), equalTo(10_000));
		assertThat(Policy.DEFAULT.refusalCostMillipoints(), equalTo(1000));
	}

	/* The rates of a network of 4 validators and 10 transactions a second: nothing above the defaults but a smaller
	   burst of heartbeats */
	@Example
	public void shouldSizeTheLimitsForFourValidators() {
		final Policy policy = Policy.forNetwork(4, 10);

		assertThat(policy.rates(), equalTo(rates(new Rate(200, 50), new Rate(8, 2), new Rate(2000, 500),
			new Rate(10, 1), new Rate(40, 10))));
		assertThat(policy.global(), equalTo(rates(new Rate(2000, 500), new Rate(500, 20), new Rate(20_000, 5000),
			new Rate(200, 20), new Rate(200, 100))));
	}

	@Example
	public void shouldSizeTheLimitsForTwentyValidators() {
		final Policy policy = Policy.forNetwork(20, 100);

		assertThat(policy.rates(), equalTo(rates(new Rate(800, 200), new Rate(40, 4), new Rate(2000, 500),
			new Rate(10, 1), new Rate(40, 10))));
		assertThat(policy.global(), equalTo(rates(new Rate(7200, 1800), new Rate(500, 36), new Rate(20_000, 5000),
			new Rate(200, 38), new Rate(200, 100))));
	}

	@Example
	public void shouldSizeTheLimitsForOneHundredValidators() {
		final Policy policy = Policy.forNetwork(100, 1000);

		assertThat(policy.rates(), equalTo(rates(new Rate(8000, 2000), new Rate(200, 20), new Rate(8080, 2020),
			new Rate(10, 1), new Rate(40, 10))));
		assertThat(policy.global(), equalTo(rates(new Rate(72_000, 18_000), new Rate(720, 180),
			new Rate(72_720, 18_180), new Rate(792, 198), new Rate(200, 100))));
	}

	@Example
	public void shouldKeepTheScoringOfTheDefaultsAndCostAQuarterOfAPointForARefusal() {
		final Policy policy = Policy.forNetwork(20, 100);

		assertThat(policy.refusalCostMillipoints(), equalTo(250));
		assertThat(policy.scoreLimit(), equalTo(Policy.DEFAULT.scoreLimit()));
		assertThat(policy.scoreDecayPerMinute(), equalTo(Policy.DEFAULT.scoreDecayPerMinute()));
		assertThat(policy.penaltyMillis(), equalTo(Policy.DEFAULT.penaltyMillis()));
		assertThat(policy.maxPeers(), equalTo(Policy.DEFAULT.maxPeers()));
	}

	@Example
	public void shouldRefuseToSizeTheLimitsForNoValidatorsOrNoTransactions() {
		assertThrows(IllegalArgumentException.class, () -> Policy.forNetwork(0, 10));
		assertThrows(IllegalArgumentException.class, () -> Policy.forNetwork(4, 0));
		assertThrows(IllegalArgumentException.class, () -> new Policy(same(new Rate(1, 1)), same(new Rate(1, 1)), 1, 0,
			1, 2, 0));
	}

	/* The shared bucket of junk is a twentieth of the one for the messages of the engine, and never smaller than a
	   token */
	@Example
	public void shouldLetAllJunkShareOneBucketThatIsATwentiethOfTheSharedOneForTheMessagesOfTheEngine() {
		assertThat(Policy.DEFAULT.junk(), equalTo(new Rate(1000, 250)));
		assertThat(new Policy(same(new Rate(1, 1)), same(new Rate(10, 10)), 1, 0, 1, 2).junk(),
			equalTo(new Rate(1, 1)));
	}

	@Example
	public void shouldTakeJunkFromTheSharedBucketOfJunkAndLeaveTheBucketOfItsKindToWhatIsPlausible() {
		final Policy policy = new Policy(same(new Rate(100, 100)), same(new Rate(100, 20)), 100, 0, 60_000, 100);
		final PeerLimiter limiter = new PeerLimiter(() -> START, policy);

		assertThat(IntStream.range(0, 20).filter(n -> limiter.allow("a" + n, Kind.CONSENSUS, false)).count(),
			equalTo(5L));
		assertThat(IntStream.range(0, 100).filter(n -> limiter.allow("b" + n, Kind.CONSENSUS, true)).count(),
			equalTo(100L));
		assertThat(IntStream.range(0, 100).filter(n -> limiter.allow("c" + n, Kind.CONSENSUS)).count(),
			equalTo(0L));
	}

	@Example
	public void shouldChargeJunkToTheOwnBucketOfItsSenderWhateverTheSharedBucketOfJunkSays() {
		final Policy policy = new Policy(same(new Rate(10, 1)), same(new Rate(100, 20)), 5, 0, 60_000, 100);
		final PeerLimiter limiter = new PeerLimiter(() -> START, policy);

		IntStream.range(0, 20).forEach(n -> limiter.allow("flooder", Kind.CONSENSUS, false));

		assertThat(limiter.isPenalised("flooder"), is(true));
	}

	@Example
	public void shouldLeaveTheOwnBucketOfAPeerAloneWhenOnlyTheSharedBucketOfWhatIsPlausibleRefuses() {
		final Policy policy = new Policy(same(new Rate(10, 1)), same(new Rate(5, 1)), 5, 0, 60_000, 100);
		final PeerLimiter limiter = new PeerLimiter(() -> START, policy);

		IntStream.range(0, 5).forEach(n -> limiter.allow("other" + n, Kind.CONSENSUS, true));

		assertThat(granted(limiter, "a", Kind.CONSENSUS, 20), equalTo(0));
		assertThat(limiter.isPenalised("a"), is(false));
	}

	@Example
	public void shouldCountEachPenaltyAndEachEndOfOneAndNameThePeersThatArePenalised() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 100));
		final long before = limiter.penaltyEpoch();

		limiter.ban("a");
		limiter.ban("b");

		assertThat(limiter.penaltyEpoch(), equalTo(before + 2));
		assertThat(limiter.penalisedIds(), equalTo(Set.of("a", "b")));

		clock.addAndGet(60_000);

		assertThat(limiter.penalisedIds().isEmpty(), is(true));
		assertThat(limiter.penaltyEpoch(), equalTo(before + 4));
		assertThat(limiter.penaltyEpoch(), equalTo(before + 4));
	}

	/* The rates in the order of the kinds: transaction, heartbeat, consensus, status and block request */
	private static Map<Kind, Rate> rates(Rate transaction, Rate heartbeat, Rate consensus, Rate status,
		Rate blockRequest) {
		return Map.of(Kind.TRANSACTION, transaction, Kind.HEARTBEAT, heartbeat, Kind.CONSENSUS, consensus,
			Kind.STATUS, status, Kind.BLOCK_REQUEST, blockRequest);
	}

	@Example
	public void shouldTakeAQuarterOfAPointOffForARefusalWhenThePolicySaysSo() {
		final PeerLimiter limiter = new PeerLimiter(() -> START,
			new Policy(same(new Rate(1, 1)), same(UNLIMITED), 100, 0, 60_000, 100, 250));

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));

		for (int refusals = 1; refusals < 400; refusals++) {
			assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
			assertThat(limiter.isPenalised("a"), is(false));
		}

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldTakeAWholePointOffForARefusalWhenThePolicyIsMadeTheOldWay() {
		final PeerLimiter limiter = new PeerLimiter(() -> START,
			new Policy(same(new Rate(1, 1)), same(UNLIMITED), 100, 0, 60_000, 100));

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(true));

		for (int refusals = 1; refusals < 100; refusals++) {
			assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
			assertThat(limiter.isPenalised("a"), is(false));
		}

		assertThat(limiter.allow("a", Kind.TRANSACTION), is(false));
		assertThat(limiter.isPenalised("a"), is(true));
	}

	@Example
	public void shouldNotPenaliseANeighbourThatRelaysEveryHeartbeatOfFortyValidatorsInOneBurstForAnHour() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.forNetwork(40, 10));

		for (int round = 0; round < 360; round++) {
			assertThat(granted(limiter, "neighbour", Kind.HEARTBEAT, 40), equalTo(40));
			clock.addAndGet(10_000);
		}

		assertThat(limiter.isPenalised("neighbour"), is(false));
	}

	@Example
	public void shouldHaveBannedThatNeighbourWithinMinutesUnderTheDefaults() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.DEFAULT);
		int rounds = 0;

		while (!limiter.isPenalised("neighbour") && rounds < 360) {
			granted(limiter, "neighbour", Kind.HEARTBEAT, 40);
			clock.addAndGet(10_000);
			rounds++;
		}

		assertThat(rounds < 30, is(true));
	}

	/* Each item reaches the node from nine peers, as it does when a node relays to at most eight others, all at once:
	   the transactions, the messages of the engine at a block a second, and the heartbeats of every validator */
	@Example
	public void shouldRefuseNoHonestPeerAndPenaliseNoneWhenFortyValidatorsRelayThroughNineCopiesForAnHour() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.forNetwork(40, 10));
		int refused = 0;

		for (int second = 0; second < 3600; second++) {
			refused += copies(limiter, Kind.TRANSACTION, 10);
			refused += copies(limiter, Kind.CONSENSUS, 82);
			refused += second % 10 == 0 ? copies(limiter, Kind.HEARTBEAT, 40) : 0;
			clock.addAndGet(1000);
		}

		assertThat(refused, equalTo(0));
		assertThat(IntStream.range(0, 39).anyMatch(peer -> limiter.isPenalised("peer" + peer)), is(false));
	}

	/* How many of the items, each relayed by nine of the 39 peers, were refused */
	private static int copies(PeerLimiter limiter, Kind kind, int items) {
		int refused = 0;

		for (int item = 0; item < items; item++) {
			for (int copy = 0; copy < 9; copy++) {
				refused += limiter.allow("peer" + (item + copy) % 39, kind) ? 0 : 1;
			}
		}

		return refused;
	}

	@Example
	public void shouldBanAPeerThatFloodsAtAHundredTimesTheHonestRateWithinTwoMinutes() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, Policy.forNetwork(40, 10));
		int seconds = 0;

		while (!limiter.isPenalised("flooder") && seconds < 120) {
			granted(limiter, "flooder", Kind.HEARTBEAT, 400);
			clock.addAndGet(1000);
			seconds++;
		}

		assertThat(limiter.isPenalised("flooder"), is(true));
		assertThat(seconds < 120, is(true));
	}
}
