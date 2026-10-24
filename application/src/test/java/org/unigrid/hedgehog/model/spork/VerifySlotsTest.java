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
import java.time.Instant;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/* Each source pays for the checks it causes from a bucket of its own, which holds at most the ceiling of a second and
   fills by a share of it each second, and the checks of a type in a second stay within the ceiling, whoever pays */
public class VerifySlotsTest {
	private static final Type TYPE = Type.MINT_SUPPLY;

	private Instant now = Instant.parse("2026-10-16T12:00:00Z");
	private final VerifySlots slots = new VerifySlots(() -> now);

	/* The checks that each of the sources given could pay for, one at a time, as many as it asks for */
	private long checks(final Type type, final String prefix, final int sources, final int asked) {
		return IntStream.range(0, sources).mapToLong(n -> IntStream.range(0, asked)
			.filter(check -> slots.take(type, prefix + n, 1)).count()).sum();
	}

	@Example
	public void letsASourcePayForItsBurstAndThenForItsShareEachSecond() {
		assertThat(checks(TYPE, "peer", 1, 1000), is((long) VerifySlots.BURST));
		assertThat(slots.hasLeft(TYPE, "peer"), is(false));

		now = now.plus(SporkLag.VERIFY_GAP);
		assertThat(checks(TYPE, "peer", 1, 1000), is((long) VerifySlots.SHARE));

		now = now.plus(SporkLag.VERIFY_GAP.dividedBy(2));
		assertThat(checks(TYPE, "peer", 1, 1000), is((long) VerifySlots.SHARE / 2));
	}

	/* A spork that costs more than a source holds waits until its bucket has filled, and is charged in full */
	@Example
	public void chargesWhatACheckCostsInFullOrNothing() {
		assertThat(slots.take(TYPE, "peer", VerifySlots.BURST + 1), is(false));
		assertThat(slots.take(TYPE, "peer", VerifySlots.BURST), is(true));
		assertThat(slots.take(TYPE, "peer", 1), is(false));

		now = now.plus(Duration.ofSeconds(1));
		assertThat(slots.take(TYPE, "peer", VerifySlots.SHARE + 1), is(false));
		assertThat(slots.take(TYPE, "peer", VerifySlots.SHARE), is(true));
	}

	@Example
	public void keepsTheSourcesAndTheTypesApart() {
		assertThat(checks(TYPE, "peer", 1, 1000), is((long) VerifySlots.BURST));
		assertThat(slots.hasLeft(TYPE, "peer"), is(false));
		assertThat(slots.hasLeft(Type.LEDGER_FEE, "peer"), is(true));

		now = now.plus(SporkLag.VERIFY_GAP);
		assertThat(slots.hasLeft(TYPE, "another"), is(true));
	}

	@Example
	public void paysForAtMostTheCeilingOfATypeInASecondWhoeverPays() {
		assertThat(checks(TYPE, "peer-", 1000, 1), is((long) VerifySlots.CEILING));
		assertThat(checks(Type.LEDGER_FEE, "peer-", 1000, 1), is((long) VerifySlots.CEILING));
		assertThat(slots.hasLeft(TYPE, "another"), is(false));

		now = now.plus(SporkLag.VERIFY_GAP);
		assertThat(checks(TYPE, "other-", 1000, 1), is((long) VerifySlots.CEILING));
	}

	/* The checks of a genuine spork are given back, so a peer that sends several in a row has each of them checked */
	@Example
	public void givesBackWhatAGenuineSporkWasChargedToItsSourceAndToTheSecond() {
		assertThat(slots.take(TYPE, "peer", VerifySlots.BURST), is(true));
		assertThat(slots.hasLeft(TYPE, "another"), is(false));

		slots.giveBack(TYPE, "peer", VerifySlots.BURST);
		assertThat(slots.hasLeft(TYPE, "another"), is(true));
		assertThat(slots.take(TYPE, "peer", VerifySlots.BURST), is(true));
	}

	@Example
	public void givesBackNoMoreThanABucketHolds() {
		slots.giveBack(TYPE, "peer", 1000);
		assertThat(checks(TYPE, "peer", 1, 1000), is((long) VerifySlots.BURST));
	}

	/* A bucket that has filled again is no different from one that was never used, and is forgotten */
	@Example
	public void holdsNoMoreSourcesThanThoseThatPaidWhileTheirBucketsFill() {
		IntStream.range(0, 30).forEach(second -> {
			checks(TYPE, "peer-" + second + "-", 1000, 1);
			checks(Type.LEDGER_FEE, "peer-" + second + "-", 1000, 1);
			now = now.plus(SporkLag.VERIFY_GAP);
		});

		final long secondsToFill = VerifySlots.BURST / VerifySlots.SHARE + 1;

		assertThat((long) slots.tracked(), lessThanOrEqualTo(2 * secondsToFill * VerifySlots.CEILING));
	}
}
