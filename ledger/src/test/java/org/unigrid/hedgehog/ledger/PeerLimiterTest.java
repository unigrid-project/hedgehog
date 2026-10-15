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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

	private static Policy uniform(int capacity, int refill, int scoreLimit, long penaltyMillis, int maxPeers) {
		final Map<Kind, Rate> rates = Arrays.stream(Kind.values())
			.collect(Collectors.toMap(Function.identity(), kind -> new Rate(capacity, refill)));

		return new Policy(rates, scoreLimit, penaltyMillis, maxPeers);
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

		assertThat(granted(limiter, "a", Kind.BLOCK_BATCH, 4), equalTo(4));
		limiter.misbehaved("a", 99);
		penalise(limiter, "a");
		clock.addAndGet(1000);

		assertThat(granted(limiter, "a", Kind.BLOCK_BATCH, 4), equalTo(4));
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

	@Example
	public void shouldKeepAPenalisedPeerWhileItIsPenalised() {
		final PeerLimiter limiter = new PeerLimiter(() -> START, uniform(5, 1, 100, 60_000, 50));

		penalise(limiter, "bad");

		for (int peer = 0; peer < 50 + 1000; peer++) {
			limiter.allow("peer" + peer, Kind.TRANSACTION);
			assertThat(limiter.isPenalised("bad"), is(true));
		}

		assertThat(limiter.tracked(), equalTo(50));
	}

	@Example
	public void shouldRefuseANewPeerWhenEveryTrackedPeerIsPenalised() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(5, 1, 100, 60_000, 3));

		List.of("a", "b", "c").forEach(peer -> penalise(limiter, peer));

		assertThat(limiter.allow("d", Kind.TRANSACTION), is(false));
		limiter.misbehaved("d", 100);
		assertThat(limiter.tracked(), equalTo(3));
		assertThat(limiter.isPenalised("a"), is(true));

		clock.addAndGet(60_000);
		assertThat(limiter.allow("d", Kind.TRANSACTION), is(true));
		assertThat(limiter.tracked(), equalTo(3));
	}

	@Example
	public void shouldDropAPeerWhosePenaltyHasEndedToMakeRoom() {
		final AtomicLong clock = new AtomicLong(START);
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(1, 1, 100, 60_000, 2));

		penalise(limiter, "a");
		clock.addAndGet(30_000);
		penalise(limiter, "b");
		clock.addAndGet(30_000);

		assertThat(limiter.allow("c", Kind.TRANSACTION), is(true));
		assertThat(limiter.isPenalised("a"), is(false));
		assertThat(limiter.isPenalised("b"), is(true));
		assertThat(limiter.tracked(), equalTo(2));
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
		final PeerLimiter limiter = new PeerLimiter(clock::get, uniform(3, 2, 50, 5000, 5));
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
	public void shouldRefuseAPolicyThatCannotWork() {
		final Map<Kind, Rate> rates = new EnumMap<>(Kind.class);
		Arrays.stream(Kind.values()).forEach(kind -> rates.put(kind, new Rate(1, 1)));

		assertThrows(IllegalArgumentException.class, () -> new Rate(0, 1));
		assertThrows(IllegalArgumentException.class, () -> new Rate(1, 0));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, 0, 1, 1));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, 1, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, 1, 1, 0));
		rates.remove(Kind.STATUS);
		assertThrows(IllegalArgumentException.class, () -> new Policy(rates, 1, 1, 1));
	}

	@Example
	public void shouldNotFollowAChangeToTheMapThePolicyWasBuiltFrom() {
		final Map<Kind, Rate> rates = new EnumMap<>(Kind.class);
		Arrays.stream(Kind.values()).forEach(kind -> rates.put(kind, new Rate(1, 1)));

		final Policy policy = new Policy(rates, 1, 1, 1);
		rates.put(Kind.STATUS, new Rate(9, 9));

		assertThat(policy.rates().get(Kind.STATUS), equalTo(new Rate(1, 1)));
		assertThrows(UnsupportedOperationException.class, () -> policy.rates().put(Kind.STATUS, new Rate(9, 9)));
	}

	@Example
	public void shouldDefaultToTheAgreedLimits() {
		final Map<Kind, Rate> rates = Policy.DEFAULT.rates();

		assertThat(rates.get(Kind.TRANSACTION), equalTo(new Rate(200, 50)));
		assertThat(rates.get(Kind.HEARTBEAT), equalTo(new Rate(20, 2)));
		assertThat(rates.get(Kind.CONSENSUS), equalTo(new Rate(2000, 500)));
		assertThat(rates.get(Kind.STATUS), equalTo(new Rate(10, 1)));
		assertThat(rates.get(Kind.BLOCK_REQUEST), equalTo(new Rate(20, 5)));
		assertThat(rates.get(Kind.BLOCK_BATCH), equalTo(new Rate(4, 2)));
		assertThat(Policy.DEFAULT.scoreLimit(), equalTo(100));
		assertThat(Policy.DEFAULT.penaltyMillis(), equalTo(60_000L));
		assertThat(Policy.DEFAULT.maxPeers(), equalTo(10_000));
	}
}
