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
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.KEYS;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* A spork of the board keys that anyone can make, of a change that follows on nothing and carries as many approvals as
   a change can, costs nothing to turn down, so one peer that sends it every second takes no turn from an honest spork.
   The release builds in keys 0 and 1, and the honest change adds key 2. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysJunkTest {
	private static final String JUNK_SOURCE = "203.0.113.9";
	private static final String HONEST_SOURCE = "198.51.100.7";

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

	private boolean receive(GridSpork spork, String source) {
		return handler.receive(SerializationUtils.clone(spork), database, pending, lag, source);
	}

	/* One change of the most approvals, with no signatures, following on nothing */
	private static BoardKeys junk() {
		final List<KeyChange.Approval> approvals = new ArrayList<>();
		final BoardKeys spork = new BoardKeys();

		IntStream.range(0, BoardKeyChain.MAX_APPROVALS).forEach(i -> approvals.add(new KeyChange.Approval(
			key(i % KEYS.size()).getPublicKey(), new byte[0])));

		spork.setTimeStamp(Instant.now());
		spork.setData(chainOf(new KeyChange(0, Instant.now(), new byte[KeyChange.DIGEST_SIZE],
			new ArrayList<>(List.of(publicKeysOf(keys(0, 1, 2)))), approvals)));
		spork.setSignature(new byte[0]);
		spork.setCosignature(new byte[0]);
		return spork;
	}

	@Example
	public void shouldCheckAndTakeAnHonestSporkWhileOnePeerSendsJunkEverySecond() {
		final BoardKeys honest = signed(chainOf(change(rootOf(keys(0, 1)), List.of(), keys(0, 1, 2),
			keys(0, 1, 2))), key(0), key(1));
		final BoardKeys junk = junk();

		for (int second = 0; second < 3; second++) {
			receive(junk, JUNK_SOURCE);
			receive(honest, HONEST_SOURCE);
			now = now.plusSeconds(1);
		}

		assertThat(database.getBoardKeys(), notNullValue());
		assertThat(database.getBoardKeys().getSignable(), equalTo(honest.getSignable()));
	}
}
