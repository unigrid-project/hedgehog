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

import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class HashingTest {
	@Property(tries = 100)
	public void shouldRankAnIdTheSameEachTime(@ForAll long seed, @ForAll String id) {
		assertThat(Hashing.rank(seed, id), equalTo(Hashing.rank(seed, id)));
	}

	@Example
	public void shouldRankNoIdAsTheSeed() {
		assertThat(Hashing.rank(42, ""), equalTo(42L));
	}

	/* Nobody can choose an id to come first without knowing the seed */
	@Property(tries = 100)
	public void shouldRankAnIdByTheSeed(@ForAll long seed, @ForAll long other, @ForAll @StringLength(min = 1) String id) {
		Assume.that(seed != other);
		assertThat(Hashing.rank(seed, id), not(equalTo(Hashing.rank(other, id))));
	}

	@Property(tries = 100)
	public void shouldRankAnIdByEachOfItsCharacters(@ForAll long seed, @ForAll @StringLength(min = 1) String id,
		@ForAll int position) {

		final int at = Math.floorMod(position, id.length());
		final char changed = (char) (id.charAt(at) + 1);
		final String other = id.substring(0, at) + changed + id.substring(at + 1);

		assertThat(Hashing.rank(seed, id), not(equalTo(Hashing.rank(seed, other))));
	}

	/* Zero is the one state that the mixing keeps, so a NUL after it changes nothing; only an id made for a known seed
	   gets there */
	@Property(tries = 100)
	public void shouldRankALongerIdApart(@ForAll long seed, @ForAll String id, @ForAll char appended) {
		Assume.that(Hashing.rank(seed, id) != 0 || appended != 0);
		assertThat(Hashing.rank(seed, id), not(equalTo(Hashing.rank(seed, id + appended))));
	}
}
