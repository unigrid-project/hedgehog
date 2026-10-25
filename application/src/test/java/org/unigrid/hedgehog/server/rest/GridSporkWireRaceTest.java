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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.RacingVersion;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* A spork that a board member hands in is judged without a lock, so another version can be stored while its
   signatures are checked. The node hands the version in as the one that wire decodes to, for the whole class, as a
   fake outlives its try. */
public class GridSporkWireRaceTest extends BaseRestClientTest {
	@Inject private SporkDatabase sporkDatabase;

	@SneakyThrows
	private static MintSupply cosignedOver(GridSpork stored, BigDecimal maxSupply, Signature signature) {
		final MintSupply spork = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(maxSupply);
		spork.sign(signature.getPrivateKey());
		spork.cosign(COSIGNER.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldStayOnAVersionStoredWhileAVersionItOutdatesWasJudged(
		@ForAll("provideSignature") Signature signature) {

		final MintSupply older = cosignedOver(sporkDatabase.getMintSupply(), new BigDecimal("20"), signature);
		final MintSupply newer = cosignedOver(older, new BigDecimal("30"), signature);
		final MintSupply racing = RacingVersion.of(older, () -> sporkDatabase.setMintSupply(newer));
		final byte[] wire = SporkWire.encode(older);

		new MockUp<SporkWire>() {
			@Mock public GridSpork decode(byte[] submitted) {
				return racing;
			}
		};

		assertThat(Status.fromStatusCode(client.post("/gridspork/wire", Entity.entity(wire,
			MediaType.APPLICATION_OCTET_STREAM)).getStatus()), equalTo(Status.CONFLICT));
		assertThat(sporkDatabase.getMintSupply(), sameInstance(newer));
	}
}
