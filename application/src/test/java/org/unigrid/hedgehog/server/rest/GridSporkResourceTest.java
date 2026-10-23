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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SignatureLogEntry;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;

public class GridSporkResourceTest extends BaseRestClientTest {
	@Inject
	private SporkDatabase sporkDatabase;

	@Inject
	private PendingSporks pendingSporks;

	@Example
	@SneakyThrows
	public void shouldBeAbleToGetGridSporkOverview() {
		final Instant now = Instant.now();

		SporkDatabaseInfo info = client.get("/gridspork").readEntity(SporkDatabaseInfo.class);
		assertThat(info.getMintStorageEntries().getLastChanged(), equalTo(SporkDatabaseInfo.LASTCHANGED_NEVER));

		sporkDatabase.setMintStorage(new MintStorage());
		sporkDatabase.getMintStorage().setData(new MintStorage.SporkData());
		sporkDatabase.getMintStorage().setTimeStamp(now);

		final MintStorage.SporkData data = ((MintStorage.SporkData) sporkDatabase.getMintStorage().getData());
		data.setMints(new HashMap<>());
		data.getMints().put(new Location(new Address("0123456789"), 1337), BigDecimal.ONE);

		info = client.get("/gridspork").readEntity(SporkDatabaseInfo.class);
		assertThat(info.getMintStorageEntries().getLastChanged(), equalTo(now.toString()));
		assertThat(info.getMintStorageEntries().getAmount(), equalTo(1));
	}

	private static final Instant SIGNED_AT = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant PREVIOUSLY_SIGNED_AT = Instant.parse("2025-01-01T00:00:00Z");

	/* A version the board signed and co-signed */
	@SneakyThrows
	private MintSupply seedMintSupply() {
		final MintSupply mintSupply = new MintSupply();
		final MintSupply.SporkData data = mintSupply.getData();

		data.setMaxSupply(BigDecimal.TEN);
		mintSupply.setTimeStamp(SIGNED_AT);
		mintSupply.setPreviousTimeStamp(PREVIOUSLY_SIGNED_AT);
		mintSupply.setPreviousData(data.empty());
		mintSupply.sign(PROPOSER.getPrivateKey());
		mintSupply.cosign(COSIGNER.getPrivateKey());

		sporkDatabase.setMintStorage(null);
		sporkDatabase.setVestingStorage(null);
		sporkDatabase.setStatisticsPubKey(null);
		sporkDatabase.setMintSupply(mintSupply);
		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);
		return mintSupply;
	}

	private void renewAndCosign(Signature signature) {
		final Response proposal = renew(GridSpork.Type.MINT_SUPPLY, signature);

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
	}

	/* The stored version enters the log when the board signs it again */
	@SneakyThrows
	@Property(tries = 3)
	public void shouldResignStoredSporksWithoutChangingTheirData(@ForAll("provideSignature") Signature signature) {
		final MintSupply original = seedMintSupply();

		assertThat(original.isTakenByTheNetwork(), is(true));

		final Response proposal = renew(GridSpork.Type.MINT_SUPPLY, signature);

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(sporkDatabase.getMintSupply(), sameInstance(original));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));

		final MintSupply renewed = sporkDatabase.getMintSupply();
		final MintSupply.SporkData data = renewed.getData();
		final SignatureLogEntry replaced = renewed.getSignatureLog().getEntries().getLast();

		assertThat(renewed.isDoublySigned(), is(true));
		assertThat(replaced.getSigners(), contains(PROPOSER.getPublicKey(), COSIGNER.getPublicKey()));
		assertThat(replaced.getTimeStamp(), equalTo(SIGNED_AT));
		assertThat(replaced.getSignature(), equalTo(original.getSignature()));
		assertThat(renewed.getTimeStamp(), greaterThan(SIGNED_AT));
		assertThat(renewed.getTimeStamp(), lessThanOrEqualTo(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
		assertThat(data.getMaxSupply(), comparesEqualTo(BigDecimal.TEN));
		assertThat(renewed.getPreviousTimeStamp(), equalTo(PREVIOUSLY_SIGNED_AT));
		assertThat(renewed.getPreviousData(), equalTo(original.getPreviousData()));
	}

	@SneakyThrows
	@Property(tries = 2)
	public void shouldShowWhoSignedEachVersion(@ForAll("provideSignature") Signature signature) {
		seedMintSupply();
		renewAndCosign(signature);

		final JsonNode log = new ObjectMapper().readTree(client.get("/gridspork/log").readEntity(String.class))
			.get(GridSpork.Type.MINT_SUPPLY.name());
		final JsonNode entry = log.get("entries").get(0);

		assertThat(log.get("entries").size(), is(1));
		assertThat(entry.get("signer").asText(), equalTo(PROPOSER.getPublicKey()));
		assertThat(entry.get("cosigner").asText(), equalTo(COSIGNER.getPublicKey()));
		assertThat(entry.get("timeStamp").asText(), equalTo(SIGNED_AT.toString()));
		assertThat(log.get("head").get("signer").asText(), equalTo(signature.getPublicKey()));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldHaveNoSignatureLogWhenDatabaseIsEmpty(@ForAll("provideSignature") Signature signature) {
		seedMintSupply();
		sporkDatabase.setMintSupply(null);
		sporkDatabase.setStorageSpork(null);
		sporkDatabase.setLedgerFee(null);
		sporkDatabase.setLedgerBlacklist(null);

		assertThat(Status.fromStatusCode(client.get("/gridspork/log").getStatus()), equalTo(Status.NO_CONTENT));
	}

	@SneakyThrows
	private MintSupply proposeMintSupplySignedBy(Signature signature) {
		final MintSupply proposal = new MintSupply();

		((MintSupply.SporkData) proposal.getData()).setMaxSupply(BigDecimal.TWO);
		proposal.archive();
		proposal.sign(signature.getPrivateKey());

		sporkDatabase.setMintSupply(null);
		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);
		pendingSporks.offer(proposal, null);
		return proposal;
	}

	@SneakyThrows
	@Property(tries = 3)
	public void shouldListProposalsWithTheirDigestAndSigner(@ForAll("provideSignature") Signature signature) {
		final MintSupply proposal = proposeMintSupplySignedBy(signature);
		final JsonNode listed = new ObjectMapper().readTree(client.get("/gridspork/pending")
			.readEntity(String.class)).get(0);

		assertThat(listed.get("type").asText(), equalTo(GridSpork.Type.MINT_SUPPLY.name()));
		assertThat(listed.get("digest").asText(), equalTo(PendingSporks.digestOf(proposal)));
		assertThat(listed.get("signer").asText(), equalTo(signature.getPublicKey()));
		assertThat(listed.get("expires").asText(),
			equalTo(proposal.getTimeStamp().plus(PendingSporks.LIFETIME).toString())
		);
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldHaveNoProposalsWhenNoneArePending(@ForAll("provideSignature") Signature signature) {
		proposeMintSupplySignedBy(signature);
		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);

		assertThat(Status.fromStatusCode(client.get("/gridspork/pending").getStatus()), equalTo(Status.NO_CONTENT));
	}

	@SneakyThrows
	private JsonNode mintSupplyLog() {
		return new ObjectMapper().readTree(client.get("/gridspork/log").readEntity(String.class))
			.get(GridSpork.Type.MINT_SUPPLY.name());
	}

	/* A board member holds the head that the signing commands show against the log of a second node: its digest is
	   the one they print the start of, and the one the next version enters in its log */
	@SneakyThrows
	@Property(tries = 1)
	public void shouldShowTheDigestOfTheHead(@ForAll("provideSignature") Signature signature) {
		seedMintSupply();
		renewAndCosign(signature);

		final String head = mintSupplyLog().get("head").get("digest").asText();

		assertThat(head, equalTo(PendingSporks.digestOf(sporkDatabase.getMintSupply())));
		renewAndCosign(signature);
		assertThat(mintSupplyLog().get("entries").get(1).get("digest").asText(), equalTo(head));
	}

	@SneakyThrows
	@Property(tries = 2)
	public void shouldShowBothSignersOfEachVersion(@ForAll("provideSignature") Signature signature) {
		seedMintSupply();
		renewAndCosign(signature);
		renewAndCosign(signature);

		final JsonNode log = new ObjectMapper().readTree(client.get("/gridspork/log").readEntity(String.class))
			.get(GridSpork.Type.MINT_SUPPLY.name());
		final JsonNode entry = log.get("entries").get(1);

		assertThat(log.get("entries").size(), is(2));
		assertThat(entry.get("signer").asText(), equalTo(signature.getPublicKey()));
		assertThat(entry.get("cosigner").asText(), equalTo(COSIGNER.getPublicKey()));
		assertThat(log.get("head").get("signer").asText(), equalTo(signature.getPublicKey()));
		assertThat(log.get("head").get("cosigner").asText(), equalTo(COSIGNER.getPublicKey()));
	}
}
