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
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;

public class GridSporkResourceTest extends BaseRestClientTest {
	@Inject
	private SporkDatabase sporkDatabase;

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
		final MintSupply original = seedMintSupplySignedBy(new Signature());

		assertThat(original.isValidSignature(), is(false));
		assertThat(Status.fromStatusCode(renew(signature).getStatus()), equalTo(Status.OK));

		final MintSupply renewed = sporkDatabase.getMintSupply();
		final MintSupply.SporkData data = renewed.getData();

		assertThat(renewed.isValidSignature(), is(true));
		assertThat(renewed.getTimeStamp(), greaterThan(SIGNED_AT));
		assertThat(renewed.getTimeStamp(), lessThanOrEqualTo(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
		assertThat(data.getMaxSupply(), equalTo(BigDecimal.TEN));
		assertThat(renewed.getPreviousTimeStamp(), equalTo(PREVIOUSLY_SIGNED_AT));
		assertThat(renewed.getPreviousData(), equalTo(original.getPreviousData()));
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
}
