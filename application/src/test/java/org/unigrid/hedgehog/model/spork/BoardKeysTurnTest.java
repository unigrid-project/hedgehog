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
import java.util.List;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* A spork of the board keys whose chain this node has not checked costs a signature for each approval of the changes
   it does not remember, and takes as many turns of its type from the second it is checked in. The release builds in
   keys 0 and 1, and the change adds key 2. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysTurnTest {
	private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

	private final SporkLag lag = new SporkLag(() -> NOW);

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private long turnsOfOthers() {
		return IntStream.range(0, VerifySlots.CEILING).filter(n -> lag.turnOf(new MintSupply(), "peer-" + n)
			.getAsBoolean()).count();
	}

	@Example
	public void shouldChargeATurnForEachApprovalThatIsNotRemembered() {
		final KeyChange adding = change(rootOf(keys(0, 1)), List.of(), keys(0, 1, 2), keys(0, 1, 2));
		final BoardKeys spork = signedAnyway(chainOf(adding), key(0), key(1));
		final SporkLag.Turn turn = lag.turnOf(spork, "peer");

		assertThat(turn.getAsBoolean(), is(true));
		assertThat(IntStream.range(0, VerifySlots.CEILING).filter(n -> lag.turnOf(Type.BOARD_KEYS, "peer-" + n)
			.getAsBoolean()).count(), is((long) VerifySlots.CEILING - 1 - adding.getApprovals().size()));
		assertThat(turnsOfOthers(), is((long) VerifySlots.CEILING));
	}

	@Example
	public void shouldChargeOneTurnForAChainThatIsRemembered() {
		final KeyChange adding = change(rootOf(keys(0, 1)), List.of(), keys(0, 1, 2), keys(0, 1, 2));
		final BoardKeys spork = signedAnyway(chainOf(adding), key(0), key(1));

		assertThat(spork.chainKeys().isPresent(), is(true));
		assertThat(lag.turnOf(spork, "peer").getAsBoolean(), is(true));
		assertThat(IntStream.range(0, VerifySlots.CEILING).filter(n -> lag.turnOf(Type.BOARD_KEYS, "peer-" + n)
			.getAsBoolean()).count(), is((long) VerifySlots.CEILING - 1));
	}
}
