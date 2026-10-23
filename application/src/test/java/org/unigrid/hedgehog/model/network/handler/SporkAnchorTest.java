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

/* The release builds in keys 0 and 1. The node holds the second version of the mint supply, whose log holds the
   first; then the board switches one of its signers off, or both. */
public class SporkAnchorTest {
	private final PublishSporkChannelHandler handler = new PublishSporkChannelHandler();
	private SporkDatabase database;
	private PendingSporks pending;
	private MintSupply first;
	private MintSupply stored;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		database = SporkDatabase.builder().build();
		pending = new PendingSporks();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static void activate(int... keys) {
		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(keys)))));
	}

	@SneakyThrows
	private static MintSupply version(MintSupply over, BigDecimal supply, long secondsAgo, int signer, int cosigner) {
		final MintSupply spork = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.setTimeStamp(Instant.now().minusSeconds(secondsAgo));
		spork.<MintSupply.SporkData>getData().setMaxSupply(supply);
		spork.sign(key(signer).getPrivateKey());
		spork.cosign(key(cosigner).getPrivateKey());
		return spork;
	}

	/* The first version by the keys given first, the second over it by the keys given then */
	private void store(int firstSigner, int firstCosigner, int signer, int cosigner) {
		first = version(null, BigDecimal.ONE, 120, firstSigner, firstCosigner);
		stored = version(first, BigDecimal.TEN, 60, signer, cosigner);
		database.set(stored);
	}

	private boolean receive(GridSpork spork) {
		return handler.receive(SerializationUtils.clone(spork), database, pending, new SporkLag(), "peer");
	}

	/* What util spork-db sign makes of the data with two keys that are active, a while after the version before */
	@SneakyThrows
	private GridSpork signedAgain(BigDecimal supply, int signer, int cosigner) {
		Thread.sleep(2);

		final GridSpork again = GridSpork.firstVersionOf(stored);

		again.<MintSupply.SporkData>getData().setMaxSupply(supply);
		again.sign(key(signer).getPrivateKey());
		again.cosign(key(cosigner).getPrivateKey());
		return again;
	}

	@SneakyThrows
	private static GridSpork proposed(GridSpork spork, int signer) {
		final GridSpork proposal = SerializationUtils.clone(spork);

		proposal.sign(key(signer).getPrivateKey());
		return proposal;
	}

	@Example
	public void shouldTakeAVersionSignedAgainInPlaceOfOneThatNoActiveKeySigned() {
		store(0, 1, 0, 1);
		activate(2, 3);

		for (BigDecimal supply : List.of(BigDecimal.TEN, BigDecimal.TWO)) {
			final GridSpork again = signedAgain(supply, 2, 3);

			assertThat(receive(again), is(true));
			assertThat(database.getMintSupply().getTimeStamp(), is(again.getTimeStamp()));
			assertThat(database.getMintSupply().isCounting(), is(true));
		}
	}

	/* A proposal is held over the version it was made from only, so the board signs the stored one again as its
	   renewal; a first version of its data, which util spork-db sign co-signs in a file, is no proposal over it */
	@Example
	@SneakyThrows
	public void shouldTakeAProposalSignedAgainOverOneThatNoActiveKeySigned() {
		store(0, 1, 0, 1);
		activate(2, 3);

		final GridSpork renewal = SerializationUtils.clone(stored);

		renewal.renew();
		renewal.sign(key(2).getPrivateKey());
		assertThat(receive(proposed(GridSpork.firstVersionOf(stored), 2)), is(false));
		assertThat(receive(renewal), is(true));
		assertThat(pending.proposalOf(Type.MINT_SUPPLY).isPresent(), is(true));
	}

	/* A key that is still active vouches for the stored version, and the version signed again does not carry it on */
	@Example
	public void shouldKeepOutAVersionThatDoesNotFollowOnOneThatAnActiveKeySigned() {
		store(0, 1, 0, 1);
		activate(1, 2);

		assertThat(receive(signedAgain(BigDecimal.TEN, 1, 2)), is(false));
		assertThat(receive(first), is(false));
		assertThat(database.getMintSupply(), sameInstance(stored));
	}

	/* Keys 0 and 1 signed the first version and stay active; keys 2 and 3 signed the second, and the board switches
	   them off. Whoever kept the first version sends it again, which must not take the data back. */
	@Example
	public void shouldNeverTakeAnOlderVersionBackInPlaceOfOneThatNoActiveKeySigned() {
		activate(0, 1, 2, 3);
		store(0, 1, 2, 3);
		activate(0, 1);

		assertThat(stored.isAnchoring(), is(false));
		assertThat(receive(first), is(false));
		assertThat(receive(proposed(first, 0)), is(false));
		assertThat(database.getMintSupply(), sameInstance(stored));
		assertThat(pending.list().isEmpty(), is(true));
		assertThat(receive(signedAgain(BigDecimal.TWO, 0, 1)), is(true));
	}
}
