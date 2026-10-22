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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

/* The stored sporks and the proposals as the command line reads them: in the form peers exchange */
public class GridSporkWireResourceTest extends BaseRestClientTest {
	@Inject private SporkDatabase sporkDatabase;
	@Inject private PendingSporks pendingSporks;

	@SneakyThrows
	private static MintSupply proposal(Signature signature, BigDecimal maxSupply) {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(maxSupply);
		spork.archive();
		spork.sign(signature.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	private Status statusOf(String location) {
		return Status.fromStatusCode(client.get(location).getStatus());
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldHandOverTheStoredSporkInTheFormPeersExchange(@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final MintSupply stored = proposal(signature, maxSupply);

		stored.cosign(COSIGNER.getPrivateKey());
		sporkDatabase.setMintSupply(stored);

		final Response response = client.get("/gridspork/wire/" + Type.MINT_SUPPLY);
		final byte[] wire = response.readEntity(byte[].class);

		assertThat(response.getMediaType(), equalTo(MediaType.APPLICATION_OCTET_STREAM_TYPE));
		assertThat(wire, equalTo(SporkWire.encode(stored)));
		assertThat(SporkWire.decode(wire).isDoublySigned(), is(true));
	}

	@Example
	public void shouldHaveNoContentForATypeThatHoldsNoSpork() {
		sporkDatabase.setLedgerFee(null);
		assertThat(statusOf("/gridspork/wire/" + Type.LEDGER_FEE), equalTo(Status.NO_CONTENT));
	}

	@Example
	public void shouldFindNoTypeThatNoSporkHas() {
		for (String type : List.of(Type.UNDEFINED.name(), "mint-supply", "NONE")) {
			assertThat(type, statusOf("/gridspork/wire/" + type), equalTo(Status.NOT_FOUND));
		}
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldHandOverAProposalByItsDigest(@ForAll("provideSignature") Signature signature,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal maxSupply) {

		final MintSupply proposal = proposal(signature, maxSupply);

		sporkDatabase.setMintSupply(null);
		pendingSporks.remove(Type.MINT_SUPPLY);
		assertThat(pendingSporks.offer(proposal, null), is(true));

		final byte[] wire = client.get("/gridspork/pending/" + PendingSporks.digestOf(proposal))
			.readEntity(byte[].class);
		final GridSpork decoded = SporkWire.decode(wire);

		assertThat(wire, equalTo(SporkWire.encode(proposal)));
		assertThat(PendingSporks.digestOf(decoded), equalTo(PendingSporks.digestOf(proposal)));
		assertThat(decoded.isPending(), is(true));
	}

	@Example
	public void shouldFindNoProposalUnderADigestNoneHas() {
		assertThat(statusOf("/gridspork/pending/" + "00".repeat(64)), equalTo(Status.NOT_FOUND));
	}
}
