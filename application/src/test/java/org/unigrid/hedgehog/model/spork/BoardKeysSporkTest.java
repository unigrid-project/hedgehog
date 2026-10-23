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

import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import org.unigrid.hedgehog.model.spork.content.SporkContent;

public class BoardKeysSporkTest {
	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	private static BoardKeys sporkWith(BoardKeys.SporkData chain) {
		final BoardKeys spork = new BoardKeys();

		spork.setData(chain);
		return spork;
	}

	@Example
	public void shouldBeAGovernedSporkOfItsOwnType() {
		final BoardKeys spork = new BoardKeys();

		assertThat(spork.getType(), is(GridSpork.Type.BOARD_KEYS));
		assertThat(GridSpork.Type.get((short) 3000), is(GridSpork.Type.BOARD_KEYS));
		assertThat((spork.getFlags() & GridSpork.Flag.GOVERNED.getValue()) != 0, is(true));
		assertThat(spork.<BoardKeys.SporkData>getData().getChanges().isEmpty(), is(true));
	}

	@Example
	public void shouldHoldTheSporkInASlotOfItsOwn() {
		final SporkDatabase database = SporkDatabase.builder().build();
		final BoardKeys spork = new BoardKeys();

		database.set(spork);
		assertThat(database.get(GridSpork.Type.BOARD_KEYS), sameInstance(spork));
		assertThat(database.getBoardKeys(), sameInstance(spork));
		assertThat(database.holdsAnotherSporkIn(GridSpork.Type.BOARD_KEYS), is(false));
	}

	@Example
	public void shouldTakeAChainThatHoldsFromTheRootOfThisNode() {
		final byte[] root = rootOf(keys(0, 1));
		final KeyChange change = change(root, List.of(), keys(0, 1, 2), keys(0, 1, 2));

		SporkContent.validate(sporkWith(chainOf(change)), null);
		SporkContent.validate(sporkWith(chainOf()), null);
	}

	@Example
	public void shouldRefuseAChainOfAnotherRootOrThatDoesNotHold() {
		final byte[] elsewhere = rootOf(keys(0, 2));
		final byte[] root = rootOf(keys(0, 1));

		assertThat(assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(sporkWith(chainOf(
			change(elsewhere, List.of(), keys(0, 1, 2), keys(0, 1, 2)))), null)).getMessage(),
			containsString("does not follow"));
		assertThat(assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(sporkWith(chainOf(
			change(root, List.of(), keys(0, 1, 2), keys(0, 2)))), null)).getMessage(),
			containsString("1 of the 2 keys"));
	}
}
