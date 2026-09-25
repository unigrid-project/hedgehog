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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
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

	@SneakyThrows
	private MintSupply seedMintSupplySignedBy(Signature signature) {
		final MintSupply mintSupply = new MintSupply();
		final MintSupply.SporkData data = mintSupply.getData();

		data.setMaxSupply(BigDecimal.TEN);
		mintSupply.setTimeStamp(SIGNED_AT);
		mintSupply.setPreviousTimeStamp(PREVIOUSLY_SIGNED_AT);
		mintSupply.setPreviousData(data.empty());
		mintSupply.sign(signature.getPrivateKey());

		sporkDatabase.setMintStorage(null);
		sporkDatabase.setVestingStorage(null);
		sporkDatabase.setStatisticsPubKey(null);
		sporkDatabase.setMintSupply(mintSupply);
		return mintSupply;
	}

	@SneakyThrows
	private Response renew(Signature signature) {
		return client.putWithHeaders("/gridspork/renew", Entity.text(""),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);
	}

	@SneakyThrows
	@Property
	public void shouldResignStoredSporksWithoutChangingTheirData(@ForAll("provideSignature") Signature signature) {
		final Signature retiredSignature = new Signature();

		retire(retiredSignature);

		final MintSupply original = seedMintSupplySignedBy(retiredSignature);

		assertThat(original.isValidSignature(), is(false));
		assertThat(Status.fromStatusCode(renew(signature).getStatus()), equalTo(Status.OK));

		final MintSupply renewed = sporkDatabase.getMintSupply();
		final MintSupply.SporkData data = renewed.getData();
		final SignatureLogEntry replaced = renewed.getSignatureLog().getEntries().getLast();

		assertThat(renewed.isValidSignature(), is(true));
		assertThat(replaced.getSigner(), equalTo(retiredSignature.getPublicKey()));
		assertThat(replaced.getTimeStamp(), equalTo(SIGNED_AT));
		assertThat(replaced.getSignature(), equalTo(original.getSignature()));
		assertThat(renewed.getTimeStamp(), greaterThan(SIGNED_AT));
		assertThat(renewed.getTimeStamp(), lessThanOrEqualTo(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
		assertThat(data.getMaxSupply(), equalTo(BigDecimal.TEN));
		assertThat(renewed.getPreviousTimeStamp(), equalTo(PREVIOUSLY_SIGNED_AT));
		assertThat(renewed.getPreviousData(), equalTo(original.getPreviousData()));
	}

	@SneakyThrows
	@Property
	public void shouldRefuseToRenewOverAnUnknownSigner(@ForAll("provideSignature") Signature signature) {
		final MintSupply original = seedMintSupplySignedBy(new Signature());

		assertThat(Status.fromStatusCode(renew(signature).getStatus()), equalTo(Status.UNAUTHORIZED));
		assertThat(sporkDatabase.getMintSupply(), sameInstance(original));
	}

	@SneakyThrows
	@Property
	public void shouldRefuseToRenewWithUntrustedKey(@ForAll("provideSignature") Signature signature) {
		final MintSupply original = seedMintSupplySignedBy(signature);

		assertThat(Status.fromStatusCode(renew(new Signature()).getStatus()), equalTo(Status.UNAUTHORIZED));
		assertThat(sporkDatabase.getMintSupply(), sameInstance(original));
	}

	@SneakyThrows
	@Property
	public void shouldHaveNothingToRenewWhenDatabaseIsEmpty(@ForAll("provideSignature") Signature signature) {
		seedMintSupplySignedBy(signature);
		sporkDatabase.setMintSupply(null);

		assertThat(Status.fromStatusCode(renew(signature).getStatus()), equalTo(Status.NO_CONTENT));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldShowWhoSignedEachVersion(@ForAll("provideSignature") Signature signature) {
		final Signature retiredSignature = new Signature();

		retire(retiredSignature);
		seedMintSupplySignedBy(retiredSignature);
		renew(signature);

		final JsonNode log = new ObjectMapper().readTree(client.get("/gridspork/log").readEntity(String.class))
			.get(GridSpork.Type.MINT_SUPPLY.name());
		final JsonNode entry = log.get("entries").get(0);

		assertThat(log.get("entries").size(), is(1));
		assertThat(entry.get("signer").asText(), equalTo(retiredSignature.getPublicKey()));
		assertThat(entry.get("timeStamp").asText(), equalTo(SIGNED_AT.toString()));
		assertThat(log.get("head").get("signer").asText(), equalTo(signature.getPublicKey()));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldHaveNoSignatureLogWhenDatabaseIsEmpty(@ForAll("provideSignature") Signature signature) {
		seedMintSupplySignedBy(signature);
		sporkDatabase.setMintSupply(null);

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
	private Response cosign(String digest, Signature signature) {
		return client.putWithHeaders("/gridspork/pending/" + digest, Entity.text(""),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);
	}

	@SneakyThrows
	@Property(tries = 5)
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
	@Property(tries = 5)
	public void shouldHaveNoProposalsWhenNoneArePending(@ForAll("provideSignature") Signature signature) {
		proposeMintSupplySignedBy(signature);
		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);

		assertThat(Status.fromStatusCode(client.get("/gridspork/pending").getStatus()), equalTo(Status.NO_CONTENT));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldStoreAProposalOnceCosigned(@ForAll("provideSignature") Signature signature) {
		final MintSupply proposal = proposeMintSupplySignedBy(signature);

		assertThat(Status.fromStatusCode(cosign(PendingSporks.digestOf(proposal), cosigner).getStatus()),
			equalTo(Status.OK)
		);

		assertThat(sporkDatabase.getMintSupply().isDoublySigned(), is(true));
		assertThat(sporkDatabase.getMintSupply().getSignable(), equalTo(proposal.getSignable()));
		assertThat(pendingSporks.list(), empty());
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldRefuseToCosignWithTheProposersKey(@ForAll("provideSignature") Signature signature) {
		final MintSupply proposal = proposeMintSupplySignedBy(signature);

		assertThat(Status.fromStatusCode(cosign(PendingSporks.digestOf(proposal), signature).getStatus()),
			equalTo(Status.CONFLICT)
		);

		assertThat(sporkDatabase.getMintSupply(), is(nullValue()));
		assertThat(pendingSporks.list(), contains(proposal));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldRefuseToCosignAnUnknownProposal(@ForAll("provideSignature") Signature signature) {
		proposeMintSupplySignedBy(signature);

		assertThat(Status.fromStatusCode(cosign("00", cosigner).getStatus()), equalTo(Status.NOT_FOUND));
		assertThat(sporkDatabase.getMintSupply(), is(nullValue()));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void shouldRefuseToCosignWithAnUntrustedKey(@ForAll("provideSignature") Signature signature) {
		final MintSupply proposal = proposeMintSupplySignedBy(signature);

		assertThat(Status.fromStatusCode(cosign(PendingSporks.digestOf(proposal), new Signature()).getStatus()),
			equalTo(Status.UNAUTHORIZED)
		);

		assertThat(sporkDatabase.getMintSupply(), is(nullValue()));
	}
}
