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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signedAnyway;

/* A chain of board keys that costs more checks than its source holds is checked as far as the source pays each time
   it comes, the changes that hold are remembered, and it is taken once the last of them is checked: a node that starts
   fresh takes the whole chain of the board from one peer. The release builds in keys 0 and 1, and the board adds key 2
   and switches it off again, change after change, as far as a chain goes. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysTurnTest {
	private static final String SOURCE = "198.51.100.7";

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();
	private Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
	private final SporkLag lag = new SporkLag(() -> now);

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static List<KeyChange> longestChain() {
		final byte[] root = rootOf(keys(0, 1));
		final List<KeyChange> chain = new ArrayList<>();

		while (chain.size() < BoardKeyChain.MAX_CHANGES) {
			chain.add(chain.size() % 2 == 0 ? change(root, chain, keys(0, 1, 2), keys(0, 1, 2))
				: change(root, chain, keys(0, 1), keys(0, 1)));
		}

		return chain;
	}

	@Example
	public void shouldTakeTheLongestChainFromOnePeerOnceItHasPaidForEveryCheck() {
		final List<KeyChange> chain = longestChain();
		final BoardKeys spork = signedAnyway(chainOf(chain.toArray(KeyChange[]::new)), key(0), key(1));
		final int approvals = chain.stream().mapToInt(change -> change.getApprovals().size()).sum();
		int seconds = 0;

		while (database.getBoardKeys() == null && seconds < 2 * approvals / VerifySlots.SHARE) {
			final double before = lag.left(Type.BOARD_KEYS, SOURCE);

			handler.receive(SerializationUtils.clone(spork), database, pending, lag, SOURCE);
			assertThat(before - lag.left(Type.BOARD_KEYS, SOURCE), lessThanOrEqualTo((double) VerifySlots.BURST));
			now = now.plusSeconds(1);
			seconds++;
		}

		assertThat(seconds, greaterThan(1));
		assertThat(database.getBoardKeys(), notNullValue());
		assertThat(database.getBoardKeys().getSignable(), equalTo(spork.getSignable()));
		assertThat(NetworkKey.getActiveKeys().active(), is(Set.of(publicKeysOf(keys(0, 1)))));
		assertThat(NetworkKey.getActiveKeys().length(), is(BoardKeyChain.MAX_CHANGES));
	}
}
