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

package org.unigrid.hedgehog.model.network.handler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

/* The release builds in keys 0 and 1. The node holds the second version of the mint supply, which they signed and
   whose log holds the first; then the board switches one of them off, or both. */
public class SporkAnchorTest {
	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private SporkDatabase database;
	private PendingSporks pending;
	private MintSupply stored;

	@BeforeTry
	@SneakyThrows
	public void before() {
		final MintSupply first = new MintSupply();

		buildIn(keys(0, 1));
		database = SporkDatabase.builder().build();
		pending = new PendingSporks();

		first.archive();
		first.setTimeStamp(Instant.now().minusSeconds(120));
		first.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.ONE);
		first.sign(key(0).getPrivateKey());
		first.cosign(key(1).getPrivateKey());

		stored = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, first, Optional.empty());
		stored.setTimeStamp(Instant.now().minusSeconds(60));
		stored.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		stored.sign(key(0).getPrivateKey());
		stored.cosign(key(1).getPrivateKey());
		database.set(stored);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static void activate(int... keys) {
		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(keys)))));
	}

	private boolean receive(GridSpork spork) {
		return handler.receive(SerializationUtils.clone(spork), database, pending, new SporkLag(), "peer");
	}

	/* What util spork-db sign makes of the stored data with two keys that are active */
	@SneakyThrows
	private GridSpork signedAgain(int signer, int cosigner) {
		final GridSpork first = GridSpork.firstVersionOf(stored);

		first.sign(key(signer).getPrivateKey());
		first.cosign(key(cosigner).getPrivateKey());
		return first;
	}

	@Example
	public void shouldTakeTheVersionSignedAgainInPlaceOfOneThatNoActiveKeySigned() {
		activate(2, 3);

		final GridSpork again = signedAgain(2, 3);

		assertThat(receive(again), is(true));
		assertThat(database.getMintSupply().getTimeStamp(), is(again.getTimeStamp()));
		assertThat(database.getMintSupply().isCounting(), is(true));
	}

	/* A key that is still active vouches for the stored version, and the version signed again does not carry it on */
	@Example
	public void shouldKeepOutAVersionThatDoesNotFollowOnOneThatAnActiveKeySigned() {
		activate(1, 2);

		assertThat(receive(signedAgain(1, 2)), is(false));
		assertThat(database.getMintSupply(), sameInstance(stored));
	}

	@Example
	@SneakyThrows
	public void shouldTakeAProposalSignedAgainOverOneThatNoActiveKeySigned() {
		activate(2, 3);

		final GridSpork again = GridSpork.firstVersionOf(stored);

		again.sign(key(2).getPrivateKey());
		assertThat(receive(again), is(true));
		assertThat(pending.proposalOf(Type.MINT_SUPPLY).isPresent(), is(true));
	}
}
