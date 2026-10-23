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

import java.time.Instant;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

public class VerifySlotsTest {
	private static final Type TYPE = Type.MINT_SUPPLY;

	private Instant now = Instant.parse("2026-10-16T12:00:00Z");
	private final VerifySlots slots = new VerifySlots(() -> now);

	private long taken(final Type type, final String prefix, final int sources) {
		return IntStream.range(0, sources).filter(n -> slots.take(type, prefix + n)).count();
	}

	@Example
	public void givesASourceOneSlotAtATimeForEachType() {
		assertThat(slots.take(TYPE, "peer"), is(true));
		assertThat(slots.take(TYPE, "peer"), is(false));
		assertThat(slots.take(Type.LEDGER_FEE, "peer"), is(true));

		now = now.plus(SporkLag.VERIFY_GAP).minusMillis(1);
		assertThat(slots.take(TYPE, "peer"), is(false));

		now = now.plusMillis(1);
		assertThat(slots.take(TYPE, "peer"), is(true));
	}

	@Example
	public void keepsTheSourcesApart() {
		assertThat(slots.take(TYPE, "peer"), is(true));
		assertThat(slots.take(TYPE, "another"), is(true));
		assertThat(slots.take(TYPE, "peer"), is(false));
	}

	@Example
	public void givesAtMostTheCeilingOfATypeInASecondAndTheNextSecondAgain() {
		assertThat(taken(TYPE, "peer-", 1000), is((long) VerifySlots.CEILING));
		assertThat(taken(Type.LEDGER_FEE, "peer-", 1000), is((long) VerifySlots.CEILING));

		now = now.plus(SporkLag.VERIFY_GAP);
		assertThat(taken(TYPE, "peer-", 1000), is((long) VerifySlots.CEILING));
	}

	@Example
	public void doesNotSpendTheCeilingOnASourceThatIsTurnedAway() {
		IntStream.range(0, 1000).forEach(n -> slots.take(TYPE, "peer"));

		assertThat(taken(TYPE, "another-", 1000), is((long) VerifySlots.CEILING - 1));
	}

	@Example
	public void givesASlotThatIsGivenBackAgainAtOnce() {
		assertThat(slots.take(TYPE, "peer"), is(true));

		slots.giveBack(TYPE, "peer");
		assertThat(slots.take(TYPE, "peer"), is(true));
		assertThat(slots.take(TYPE, "peer"), is(false));
	}

	@Example
	public void returnsASlotThatIsGivenBackToTheCeiling() {
		assertThat(taken(TYPE, "peer-", VerifySlots.CEILING), is((long) VerifySlots.CEILING));
		assertThat(slots.take(TYPE, "another"), is(false));

		slots.giveBack(TYPE, "peer-0");
		slots.giveBack(TYPE, "peer-0");
		assertThat(slots.take(TYPE, "another"), is(true));
		assertThat(slots.take(TYPE, "yet another"), is(false));
	}

	/* A spork that costs more than one check, as one of the board keys does, is charged as many, but never more than
	   the share of a source, so that one peer leaves the rest of a second to the others */
	@Example
	public void chargesASporkForTheChecksItCostsAndNeverMoreThanTheShareOfASource() {
		assertThat(slots.take(TYPE, "peer", 5), is(true));
		assertThat(taken(TYPE, "peer-", 1000), is((long) VerifySlots.CEILING - 5));

		now = now.plus(SporkLag.VERIFY_GAP);
		assertThat(slots.take(TYPE, "costly", 1000), is(true));
		slots.giveBack(TYPE, "costly");
		assertThat(slots.take(TYPE, "costly", 1000), is(true));
		assertThat(taken(TYPE, "peer-", 1000), is((long) VerifySlots.CEILING - VerifySlots.SHARE));
	}

	@Example
	public void givesBackNothingForASlotThatWasNotGiven() {
		assertThat(taken(TYPE, "peer-", VerifySlots.CEILING), is((long) VerifySlots.CEILING));

		slots.giveBack(TYPE, "another");
		assertThat(slots.take(TYPE, "another"), is(false));
	}

	@Example
	public void holdsNoMoreSourcesThanTheSlotsOfTwoSeconds() {
		IntStream.range(0, 30).forEach(second -> {
			taken(TYPE, "peer-" + second + "-", 1000);
			taken(Type.LEDGER_FEE, "peer-" + second + "-", 1000);
			now = now.plus(SporkLag.VERIFY_GAP);
		});

		assertThat(slots.tracked(), lessThanOrEqualTo(2 * 2 * VerifySlots.CEILING));
	}
}
