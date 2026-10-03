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

import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class ValidatorSetTest {
	private static AccountKey key(int n) {
		return new AccountKey(ByteBuffer.allocate(AccountKey.SIZE).putInt(n).array());
	}

	private static ValidatorSet setOf(int size) {
		return new ValidatorSet(IntStream.range(0, size).mapToObj(ValidatorSetTest::key).toList());
	}

	@Example
	public void shouldHoldItsKeysInOrder() {
		final ValidatorSet set = new ValidatorSet(List.of(key(2), key(1)));

		assertThat(set.keys(), equalTo(List.of(key(2), key(1))));
		assertThat(set.size(), equalTo(2));
		assertThat(set.contains(key(1)), is(true));
		assertThat(set.contains(key(3)), is(false));
	}

	@Example
	public void shouldRefuseAnEmptySet() {
		assertThrows(IllegalArgumentException.class, () -> new ValidatorSet(List.of()));
	}

	@Example
	public void shouldRefuseADuplicateKey() {
		assertThrows(IllegalArgumentException.class, () -> new ValidatorSet(List.of(key(1), key(1))));
	}

	@Example
	public void shouldRefuseMoreThanTheMaximum() {
		assertThrows(IllegalArgumentException.class, () -> setOf(ValidatorSet.MAX_SIZE + 1));
		assertThat(setOf(ValidatorSet.MAX_SIZE).size(), equalTo(ValidatorSet.MAX_SIZE));
	}

	/* The smallest count that is more than two thirds of the set */
	@Example
	public void shouldNeedMoreThanTwoThirdsForAQuorum() {
		assertThat(setOf(1).quorum(), equalTo(1));
		assertThat(setOf(2).quorum(), equalTo(2));
		assertThat(setOf(3).quorum(), equalTo(3));
		assertThat(setOf(4).quorum(), equalTo(3));
		assertThat(setOf(7).quorum(), equalTo(5));
		assertThat(setOf(100).quorum(), equalTo(67));
	}

	@Property(tries = 100)
	public void shouldHaveAQuorumOfMoreThanTwoThirdsAndNoMore(@ForAll @IntRange(min = 1, max = 1000) int size) {
		final int quorum = setOf(size).quorum();

		assertThat(quorum * 3, greaterThan(size * 2));
		assertThat((quorum - 1) * 3, lessThanOrEqualTo(size * 2));
	}

	@Example
	public void shouldRotateTheProposerOverTheHeights() {
		final ValidatorSet set = setOf(3);

		assertThat(set.proposerAt(1), equalTo(key(0)));
		assertThat(set.proposerAt(2), equalTo(key(1)));
		assertThat(set.proposerAt(3), equalTo(key(2)));
		assertThat(set.proposerAt(4), equalTo(key(0)));
	}

	@Example
	public void shouldRefuseAProposerForHeightZero() {
		assertThrows(IllegalArgumentException.class, () -> setOf(3).proposerAt(0));
	}
}
