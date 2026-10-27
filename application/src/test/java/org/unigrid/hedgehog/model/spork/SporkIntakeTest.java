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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkIntake.Outcome;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;

/* Both the peers and the REST interface take a co-signed spork through it. The release builds in keys 0 and 1. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SporkIntakeTest {
	private static final Instant IN_2100 = Instant.parse("2100-01-01T00:00:00Z");

	private SporkDatabase database;
	private PendingSporks pending;
	private SporkLag lag;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		database = SporkDatabase.builder().build();
		pending = new PendingSporks();
		lag = new SporkLag();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private Outcome take(GridSpork spork) {
		return SporkIntake.take(SerializationUtils.clone(spork), database.get(spork.getType()), database, pending,
			lag.turnOf(spork.getType(), SporkLag.LOCAL)
		);
	}

	@SneakyThrows
	private static <S extends GridSpork> S stamped(S spork, Instant at) {
		spork.setTimeStamp(at);
		spork.sign(key(0).getPrivateKey());
		spork.cosign(key(1).getPrivateKey());
		return spork;
	}

	private static MintSupply supply(long maxSupply, Instant at) {
		final MintSupply spork = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		return stamped(spork, at);
	}

	@Example
	public void shouldStoreAVersionThatMayReplaceTheStoredOne() {
		final MintSupply version = supply(10, Instant.now().minusSeconds(1));

		assertThat(take(version), equalTo(Outcome.STORED));
		assertThat(database.getMintSupply().getSignable(), equalTo(version.getSignable()));
	}

	@Example
	public void shouldTurnDownAVersionStampedFarAhead() {
		assertThat(take(supply(10, IN_2100)), equalTo(Outcome.AHEAD));
		assertThat(database.getMintSupply(), nullValue());
	}

	@Example
	public void shouldTurnDownAVersionThatDoesNotFollowOnTheStoredOne() {
		final MintSupply stored = supply(20, Instant.now().minusSeconds(1));

		assertThat(take(stored), equalTo(Outcome.STORED));
		assertThat(take(supply(10, Instant.now().minusSeconds(5))), equalTo(Outcome.NOT_NEWER));
		assertThat(database.getMintSupply().getSignable(), equalTo(stored.getSignable()));
	}

	@Example
	public void shouldTurnDownBoardKeysStampedFarAhead() {
		final byte[] root = rootOf(keys(0, 1));
		final List<KeyChange> chain = List.of(change(root, List.of(), keys(0, 1, 2), keys(0, 1, 2)));
		final BoardKeys ahead = new BoardKeys();

		ahead.archive();
		ahead.setData(chainOf(chain.toArray(KeyChange[]::new)));
		assertThat(take(stamped(ahead, IN_2100)), equalTo(Outcome.AHEAD));
		assertThat(database.getBoardKeys(), nullValue());
	}
}
