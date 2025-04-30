/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class MintTest {
	private static final AccountKey RECIPIENT = new AccountKey(new byte[32]);
	private static final Reference REFERENCE = new Reference(new byte[32]);

	@Property(tries = 100)
	public void shouldBeEqualByContent(@ForAll long seed) {
		final Mint mint = CodecFixtures.mint(new Random(seed));
		final Mint same = new Mint(mint.recipient(), mint.amount(), new Reference(mint.reference().bytes()));

		assertThat(mint, equalTo(same));
		assertThat(mint.hashCode(), equalTo(same.hashCode()));
		assertThat(mint.id(), equalTo(same.id()));
	}

	@Property(tries = 50)
	public void shouldRefuseAnAmountThatIsNotPositive(@ForAll @LongRange(min = Long.MIN_VALUE, max = 0) long amount) {
		assertThrows(IllegalArgumentException.class, () -> new Mint(RECIPIENT, amount, REFERENCE));
	}

	@Example
	public void shouldEncodeKindThenFieldsInFixedWidths() {
		final byte[] encoded = new Mint(RECIPIENT, 5, REFERENCE).encode();

		assertThat(encoded.length, equalTo(Mint.ENCODED_SIZE));
		assertThat(encoded[0], equalTo(Mint.KIND));
		assertThat(new Mint(RECIPIENT, 5, REFERENCE).kind(), equalTo(Mint.KIND));
	}

	@Example
	public void shouldGiveEveryFieldItsPartInTheId() {
		final byte[] other = new byte[32];

		other[0] = 1;

		final byte[] base = new Mint(RECIPIENT, 5, REFERENCE).id();

		assertThat(new Mint(new AccountKey(other), 5, REFERENCE).id(), not(equalTo(base)));
		assertThat(new Mint(RECIPIENT, 6, REFERENCE).id(), not(equalTo(base)));
		assertThat(new Mint(RECIPIENT, 5, new Reference(other)).id(), not(equalTo(base)));
	}
}
