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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;

/* A spork from a peer is judged against the stored one without a lock, so two versions that come in at once are
   judged side by side, and the node then ends on what storing them one after the other leaves it on. The release
   builds in keys 0 and 1, the node stores a first version, and the next one is logged by the one after it. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ReplaceRaceTest {
	private static final String SOURCE = "198.51.100.7";
	private static final String OTHER = "203.0.113.9";

	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private final SporkDatabase database = SporkDatabase.builder().build();
	private final PendingSporks pending = new PendingSporks();
	private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
	private final SporkLag lag = new SporkLag(() -> now);
	private MintSupply stored;
	private MintSupply older;
	private MintSupply newer;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		stored = version(null, 10, 60);
		older = version(stored, 20, 30);
		newer = version(older, 30, 10);
		database.set(stored);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private MintSupply version(GridSpork over, long maxSupply, long secondsAgo) {
		final MintSupply spork = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		spork.setTimeStamp(now.minusSeconds(secondsAgo));
		spork.sign(key(0).getPrivateKey());
		spork.cosign(key(1).getPrivateKey());
		return spork;
	}

	private boolean received(GridSpork spork, String source) {
		return handler.receive(spork, database, pending, lag, source);
	}

	@Example
	public void shouldStayOnAVersionStoredWhileAVersionItOutdatesWasJudged() {
		assertThat(received(RacingVersion.of(older, () -> received(newer, OTHER)), SOURCE), is(false));
		assertThat(database.getMintSupply(), sameInstance(newer));
	}

	@Example
	public void shouldStoreAVersionThatOutdatesTheOneStoredWhileItWasJudged() {
		final MintSupply racing = RacingVersion.of(newer, () -> received(older, OTHER));

		assertThat(received(racing, SOURCE), is(true));
		assertThat(database.getMintSupply(), sameInstance(racing));
	}
}
