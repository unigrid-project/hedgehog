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
import java.util.Set;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.checkpointAt;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.withoutCheckpoint;

/* A release vouches for the chain of board keys up to a change, so that a quorum of keys that the board switched off
   long ago cannot fork a node that starts fresh. The release builds in keys 0 and 1 and vouches for the first change,
   which adds key 2; the second switches key 0 off, and another first change adds key 3. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysCheckpointTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange ELSEWHERE = change(ROOT, List.of(), keys(0, 1, 3), keys(0, 1, 3));

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		withoutCheckpoint();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@Example
	public void shouldTakeOnlyAChainThatHoldsTheCheckpoint() {
		checkpointAt(List.of(ADDING), true);

		assertThat(BoardKeyChain.verified(List.of(ADDING)).isPresent(), is(true));
		assertThat(BoardKeyChain.verified(List.of(ADDING, SWITCHING_OFF)).isPresent(), is(true));
		assertThat(BoardKeyChain.verified(List.of()).isPresent(), is(false));
		assertThat(BoardKeyChain.verified(List.of(ELSEWHERE)).isPresent(), is(false));
		assertThat(assertThrows(IllegalArgumentException.class, () -> BoardKeyChain.verify(List.of(ELSEWHERE)))
			.getMessage(), containsString("checkpoint"));
	}

	/* What a peer sends goes to BoardKeysAuthority, and what is handed over REST is judged by its content first */
	@Example
	public void shouldTakeNoForkFromAPeerOrOverRest() {
		final BoardKeys fork = signedAnyway(chainOf(ELSEWHERE), key(0), key(1));
		final SporkDatabase database = SporkDatabase.builder().build();

		checkpointAt(List.of(ADDING), true);
		assertThat(fork.isTakenByTheNetwork(), is(false));
		assertThat(BoardKeysAuthority.accept(fork, database, new PendingSporks(), () -> true), is(false));
		assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(fork, null));
		assertThat(BoardKeysAuthority.accept(signed(chainOf(ADDING), key(0), key(1)), database,
			new PendingSporks(), () -> true), is(true));
	}

	@Example
	public void shouldGoWithoutTheCheckpointOnANetworkOfKeysOfItsOwn() {
		checkpointAt(List.of(ADDING), false);
		assertThat(BoardKeyChain.verified(List.of(ELSEWHERE)).isPresent(), is(true));
	}

	/* The release vouches for a chain from the keys it builds in, which a node goes by unless it is told other keys */
	@Example
	public void shouldVouchForAChainFromTheKeysBuiltIntoTheRelease() {
		assertThat(BoardKeyChain.THE_RELEASE.checkpoint(), is(Network.BOARD_KEYS_CHECKPOINT));
		OptionsCli.parse(List.of("util"));
		assertThat(Set.of(NetOptions.getNetworkKeys()), is(BoardKeyChain.THE_RELEASE.builtInKeys()));
	}
}
