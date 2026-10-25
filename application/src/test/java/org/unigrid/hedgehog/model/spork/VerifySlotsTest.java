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

package org.unigrid.hedgehog.model.spork;

import java.time.Duration;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkLag.Pool;

/* Each source pays for the checks it causes from a bucket of its own, which holds the share of its pool and fills by it
   each second, and the checks of a second stay within the ceiling of the type and the one of the node, of which the
   peers that this node dialed share three quarters and every other peer the quarter left */
public class VerifySlotsTest {
	private static final Type TYPE = Type.MINT_SUPPLY;
	private static final long SECOND = Duration.ofSeconds(1).toNanos();

	private long now = 1000 * SECOND;
	private final VerifySlots slots = new VerifySlots(() -> now);

	/* The checks that each of the sources given could pay for, one at a time, as many as it asks for; one source is
	   named as given */
	private long checks(Type type, Pool pool, String prefix, int sources, int asked) {
		return IntStream.range(0, sources).mapToObj(n -> sources == 1 ? prefix : prefix + n)
			.mapToLong(source -> IntStream.range(0, asked).filter(check -> slots.take(type, pool, source)
			.isPresent()).count()).sum();
	}

	@Example
	public void letsASourcePayForItsShareAndThenForItsShareEachSecond() {
		assertThat(checks(TYPE, Pool.OTHER, "peer", 1, 1000), is((long) VerifySlots.shareOf(Pool.OTHER)));
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "peer"), is(false));

		now += SECOND;
		assertThat(checks(TYPE, Pool.OTHER, "peer", 1, 1000), is((long) VerifySlots.shareOf(Pool.OTHER)));

		now += SECOND / 2;
		assertThat(checks(TYPE, Pool.OTHER, "peer", 1, 1000), is((long) VerifySlots.shareOf(Pool.OTHER) / 2));
	}

	@Example
	public void keepsTheSourcesTheTypesAndThePoolsApart() {
		assertThat(checks(TYPE, Pool.TRUSTED, "peer", 1, 1000), is((long) VerifySlots.shareOf(Pool.TRUSTED)));
		assertThat(slots.hasLeft(TYPE, Pool.TRUSTED, "peer"), is(false));
		assertThat(slots.hasLeft(Type.LEDGER_FEE, Pool.TRUSTED, "peer"), is(true));
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "peer"), is(true));
		assertThat(slots.hasLeft(TYPE, Pool.TRUSTED, "another"), is(true));
		assertThat(checks(TYPE, Pool.OTHER, "inbound", 1, 1000), is((long) VerifySlots.shareOf(Pool.OTHER)));
	}

	/* An inbound source draws as much from its bucket for every type as from the one of a type, so a type that it
	   spent leaves it nothing for the others */
	@Example
	public void leavesAnInboundSourceThatSpentOneTypeNothingForAnother() {
		assertThat(checks(TYPE, Pool.OTHER, "peer", 1, 1000), is((long) VerifySlots.shareOf(Pool.OTHER)));
		assertThat(slots.hasLeft(Type.LEDGER_FEE, Pool.OTHER, "peer"), is(false));
		assertThat(slots.hasLeft(Type.LEDGER_FEE, Pool.OTHER, "another"), is(true));
	}

	/* However many inbound peers there are, they spend their quarter, and the peers that this node dialed the rest */
	@Example
	public void paysForAtMostTheShareOfAPoolOfTheCeilingOfATypeInASecond() {
		assertThat(checks(TYPE, Pool.OTHER, "peer-", 1000, 1000), is((long) VerifySlots.ceilingOf(Pool.OTHER,
			VerifySlots.TYPE_CEILING)));
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(false));
		assertThat(checks(TYPE, Pool.TRUSTED, "dialed-", 1000, 1000), is((long) VerifySlots.ceilingOf(Pool.TRUSTED,
			VerifySlots.TYPE_CEILING)));
		assertThat(VerifySlots.ceilingOf(Pool.OTHER, VerifySlots.TYPE_CEILING) + VerifySlots.ceilingOf(Pool.TRUSTED,
			VerifySlots.TYPE_CEILING), is(VerifySlots.TYPE_CEILING));

		now += SECOND;
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(true));
	}

	/* A check is paid for in the second whose ceiling it was judged by, also when the clock reads the next second
	   before the payment is made */
	@Example
	public void paysForNoCheckBeyondTheCeilingOfTheSecondItWasJudgedIn() {
		final VerifySlots ticking = new VerifySlots(() -> now++);
		final int ceiling = VerifySlots.ceilingOf(Pool.OTHER, VerifySlots.TYPE_CEILING);

		IntStream.range(0, ceiling).forEach(n -> ticking.take(TYPE, Pool.OTHER, "peer-" + n));
		now = 1001 * SECOND - 1;
		assertThat(ticking.take(TYPE, Pool.OTHER, "another"), is(OptionalLong.empty()));
	}

	@Example
	public void paysForAtMostTheShareOfAPoolOfTheCeilingOfTheNodeInASecondOverEveryType() {
		final long paid = Arrays.stream(Type.values()).mapToLong(type -> checks(type, Pool.OTHER, "peer-", 1000, 1))
			.sum();

		assertThat(paid, is((long) VerifySlots.ceilingOf(Pool.OTHER, VerifySlots.NODE_CEILING)));
		assertThat(slots.hasLeft(Type.LEDGER_FEE, Pool.TRUSTED, "dialed"), is(true));
	}

	/* An account pays for the checks of every type from one bucket besides, which holds an eighth of what its pool may
	   spend on the node in a second, so that it takes eight accounts to spend that, whatever types they send */
	@Example
	public void holdsAnAccountToAnEighthOfWhatItsPoolSpendsOnTheNodeOverEveryType() {
		for (Pool pool : Pool.values()) {
			final long paid = Arrays.stream(Type.values()).mapToLong(type -> checks(type, pool, "flood", 1, 1000))
				.sum();

			assertThat(pool.name(), paid, is((long) VerifySlots.ceilingOf(pool, VerifySlots.NODE_CEILING) / 8));
		}

		Arrays.stream(Type.values()).forEach(type -> checks(type, Pool.TRUSTED, "another flood", 1, 1000));
		assertThat(Arrays.stream(Type.values()).allMatch(type -> slots.hasLeft(type, Pool.TRUSTED, "honest")), is(true));
	}

	/* What a genuine spork was charged is given back, so a peer that sends several in a row has each of them checked */
	@Example
	public void givesBackWhatAGenuineSporkWasChargedToItsSourceAndToTheSecond() {
		final int share = VerifySlots.shareOf(Pool.OTHER);
		final OptionalLong paidIn = slots.take(TYPE, Pool.OTHER, "peer");

		checks(TYPE, Pool.OTHER, "peer", 1, share - 1);
		checks(TYPE, Pool.OTHER, "other-", 1000, 1);
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(false));

		slots.giveBack(TYPE, Pool.OTHER, "peer", share, paidIn.getAsLong(), share);
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(true));
		assertThat(slots.left(TYPE, Pool.OTHER, "peer"), is((double) share));
	}

	/* A second that has passed holds nothing to give back, and the next one holds none of what it charged */
	@Example
	public void givesBackNothingToASecondThatTheChecksWereNotPaidIn() {
		final int share = VerifySlots.shareOf(Pool.OTHER);
		final OptionalLong paidIn = slots.take(TYPE, Pool.OTHER, "peer");

		checks(TYPE, Pool.OTHER, "peer", 1, share - 1);
		now += SECOND;
		checks(TYPE, Pool.OTHER, "other-", 1000, 1);
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(false));

		slots.giveBack(TYPE, Pool.OTHER, "peer", share, paidIn.getAsLong(), share);
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(false));
		assertThat(slots.left(TYPE, Pool.OTHER, "peer"), is((double) share));
	}

	@Example
	public void givesBackNoMoreThanABucketHolds() {
		slots.giveBack(TYPE, Pool.OTHER, "peer", 1000, now / SECOND, 0);
		assertThat(checks(TYPE, Pool.OTHER, "peer", 1, 1000), is((long) VerifySlots.shareOf(Pool.OTHER)));
	}

	/* The monotonic clock of a process may stand anywhere, below zero too */
	@Example
	public void countsTheSecondsOfAClockThatStandsBelowZero() {
		now = -SECOND / 2;
		assertThat(checks(TYPE, Pool.OTHER, "peer-", 1000, 1), is((long) VerifySlots.ceilingOf(Pool.OTHER,
			VerifySlots.TYPE_CEILING)));

		now += SECOND;
		assertThat(slots.hasLeft(TYPE, Pool.OTHER, "another"), is(true));
	}

	/* A bucket that has filled again is no different from one that was never used, and is forgotten */
	@Example
	public void holdsNoMoreSourcesThanThoseThatPaidWhileTheirBucketsFill() {
		IntStream.range(0, 30).forEach(second -> {
			checks(TYPE, Pool.OTHER, "peer-" + second + "-", 1000, 1);
			checks(Type.LEDGER_FEE, Pool.TRUSTED, "dialed-" + second + "-", 1000, 1);
			now += SECOND;
		});

		assertThat((long) slots.tracked(), lessThanOrEqualTo(2L * VerifySlots.NODE_CEILING));
	}
}
