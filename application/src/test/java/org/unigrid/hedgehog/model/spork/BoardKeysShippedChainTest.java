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

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.asReleased;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.shipping;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* A release ships the chain of board keys up to a change, so that a quorum of keys that the board switched off long ago
   cannot fork a node that starts fresh, and no chain means the shipped one. The release builds in keys 0 and 1 and ships
   the first change, which adds key 2; the second switches key 0 off, and another first change adds key 3. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysShippedChainTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange ELSEWHERE = change(ROOT, List.of(), keys(0, 1, 3), keys(0, 1, 3));

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		asReleased();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@Example
	public void shouldTakeOnlyAChainThatHoldsTheShippedOne() {
		shipping(List.of(ADDING), true);

		assertThat(BoardKeyChain.verified(List.of(ADDING)).isPresent(), is(true));
		assertThat(BoardKeyChain.verified(List.of(ADDING, SWITCHING_OFF)).isPresent(), is(true));
		assertThat(BoardKeyChain.verified(List.of()).isPresent(), is(false));
		assertThat(BoardKeyChain.verified(List.of(ELSEWHERE)).isPresent(), is(false));
		assertThat(assertThrows(IllegalArgumentException.class, () -> BoardKeyChain.verify(List.of(ELSEWHERE)))
			.getMessage(), containsString("ships"));
	}

	/* What a peer sends goes to BoardKeysAuthority, and what is handed over REST is judged by its content first */
	@Example
	public void shouldTakeNoForkFromAPeerOrOverRest() {
		final BoardKeys fork = signedAnyway(chainOf(ELSEWHERE), key(0), key(1));
		final SporkDatabase database = SporkDatabase.builder().build();

		shipping(List.of(ADDING), true);
		assertThat(fork.isTakenByTheNetwork(), is(false));
		assertThat(BoardKeysAuthority.accept(fork, database, new PendingSporks(), () -> true), is(false));
		assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(fork, null));
		assertThat(BoardKeysAuthority.accept(signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2)), database,
			new PendingSporks(), () -> true), is(true));
	}

	/* A node that holds no chain, as one that starts fresh, goes by the keys of the shipped chain and not by those built
	   into the release */
	@Example
	public void shouldGoByTheShippedChainWhenNoChainIsHeld() {
		shipping(List.of(ADDING), true);
		BoardKeysAuthority.installTheRelease();

		assertThat(NetworkKey.getActiveKeys().active(), equalTo(Set.of(publicKeysOf(keys(0, 1, 2)))));
		assertThat(NetworkKey.getActiveKeys().length(), is(1));
	}

	@Example
	public void shouldRefuseToStartFromAShippedChainThatDoesNotHold() {
		shipping(List.of(change(ROOT, List.of(), keys(0, 1, 2), keys(0, 2))), true);
		assertThrows(IllegalStateException.class, BoardKeysAuthority::installTheRelease);
	}

	@Example
	public void shouldGoWithoutTheShippedChainOnANetworkOfKeysOfItsOwn() {
		shipping(List.of(ADDING), false);
		BoardKeysAuthority.installTheRelease();

		assertThat(BoardKeyChain.verified(List.of(ELSEWHERE)).isPresent(), is(true));
		assertThat(NetworkKey.getActiveKeys().length(), is(0));
	}

	/* The release ships the chain in the form it travels in, and this one ships none */
	@Example
	public void shouldReadTheShippedChainInTheFormItTravels() {
		final BoardKeyChain.Release read = BoardKeyChain.Release.read(new ByteArrayInputStream(BoardKeyChain.encoded(
			List.of(ADDING, SWITCHING_OFF))), Set.of());

		assertThat(read.chain().size(), is(2));
		assertThat(BoardKeyChain.holds(read.chain(), List.of(ADDING, SWITCHING_OFF)), is(true));

		asReleased();
		assertThat(BoardKeyChain.shipped(), is(empty()));
	}

	/* A release whose chain does not read is broken, which the node says when it starts, naming what it ships */
	@Example
	public void shouldNameTheChainOfAReleaseThatDoesNotRead() {
		final byte[] encoded = BoardKeyChain.encoded(List.of(ADDING, SWITCHING_OFF));

		for (byte[] broken : List.of(new byte[] { 1, 2, 3 }, Arrays.copyOf(encoded, encoded.length - 1))) {
			assertThat(assertThrows(IllegalStateException.class, () -> BoardKeyChain.Release.read(
				new ByteArrayInputStream(broken), Set.of())).getMessage(), allOf(containsString(
				Network.BOARD_KEYS_RESOURCE), containsString("the release is broken")));
		}
	}
}
