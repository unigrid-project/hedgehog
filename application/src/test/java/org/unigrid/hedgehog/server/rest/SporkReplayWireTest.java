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

package org.unigrid.hedgehog.server.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

/* A version that someone kept, handed to the node over REST after the board switched off the keys of the version that
   followed it. The release builds in keys 0 and 1; keys 2 and 3 are added, sign the second version and are switched
   off again. */
public class SporkReplayWireTest extends BaseRestClientTest {
	@Inject private SporkDatabase sporkDatabase;
	@Inject private PendingSporks pendingSporks;

	@AfterTry
	public void forget() {
		NetworkKey.reset();
		sporkDatabase.setMintSupply(null);
		pendingSporks.remove(Type.MINT_SUPPLY);
	}

	private static void activate(int... keys) {
		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(keys)))));
	}

	@SneakyThrows
	private static MintSupply version(MintSupply over, long secondsAgo, int signer, int cosigner) {
		final MintSupply spork = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.setTimeStamp(Instant.now().minusSeconds(secondsAgo));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(secondsAgo));
		spork.sign(key(signer).getPrivateKey());
		spork.cosign(key(cosigner).getPrivateKey());
		return spork;
	}

	@Example
	@SneakyThrows
	public void shouldNeverTakeAnOlderVersionBackInPlaceOfOneThatNoActiveKeySigned() {
		buildIn(keys(0, 1));
		activate(0, 1, 2, 3);

		final MintSupply first = version(null, 120, 0, 1);
		final MintSupply second = version(first, 60, 2, 3);
		final MintSupply proposal = SerializationUtils.clone(first);

		sporkDatabase.setMintSupply(second);
		activate(0, 1);
		proposal.sign(key(0).getPrivateKey());

		assertThat(Status.fromStatusCode(submit(first).getStatus()), equalTo(Status.CONFLICT));
		assertThat(Status.fromStatusCode(submit(proposal).getStatus()), equalTo(Status.CONFLICT));
		assertThat(sporkDatabase.getMintSupply().getTimeStamp(), equalTo(second.getTimeStamp()));
		assertThat(pendingSporks.proposalOf(Type.MINT_SUPPLY).isPresent(), is(false));
	}
}
