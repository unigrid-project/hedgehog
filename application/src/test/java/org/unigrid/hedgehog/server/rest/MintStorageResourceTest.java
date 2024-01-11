/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.UniqueElements;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.ValidatorSpork;

public class MintStorageResourceTest extends BaseRestClientTest {
	@Provide
	public Arbitrary<Location> provideLocation(@ForAll @AlphaChars @StringLength(36) String address,
		@ForAll @Positive int height) {

		return Arbitraries.of(Location.builder().height(height).address(
			Address.builder().wif(address).build()
		).build());
	}

	@Provide
	public Arbitrary<String> providePubKey(@ForAll @AlphaChars @StringLength(36) String pubKey) {
		return Arbitraries.of(pubKey);
	}

	@SneakyThrows
	@Property(tries = 30, shrinking = ShrinkingMode.OFF)
	public void shoulBeVerifiableInList(@ForAll("provideSignature") Signature signature,
		@ForAll @UniqueElements @Size(5) List<@From("provideLocation") Location> locations,
		@ForAll @BigRange(min = "0", max = "1000000") BigDecimal amount) {

		final String url = "/gridspork/mint-storage/";
		final Response response = client.get(url);
		int originalNumMints = 0;
		int newMints = 0;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			final MintStorage.SporkData data = response.readEntity(MintStorage.class).getData();
			originalNumMints = data.getMints().size();
		}

		for (Location l : locations) {
			final Response putResponse = client.putWithHeaders(url + l.getAddress().getWif() + "/" + l.getHeight(),
				Entity.text(amount), new MultivaluedHashMap(Map.of("privateKey",
				signature.getPrivateKey()))
			);

			if (Status.fromStatusCode(putResponse.getStatus()) == Status.OK) {
				newMints++;
			}
		}

		if (newMints > 0) {
			final MintStorage.SporkData data = client.getEntity(url, MintStorage.class).getData();
			assertThat(data.getMints().size(), equalTo(originalNumMints + newMints));
		}
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shoulBeAbleToGetMintStorageSpork(@ForAll("provideSignature") Signature signature,
		@ForAll("provideLocation") Location location, @ForAll @BigRange(min = "0") BigDecimal amount) {

		final int height = location.getHeight();
		final String wif = location.getAddress().getWif();
		final String url = "/gridspork/mint-storage/%s/%d".formatted(wif, height);

		Status expectedStatusFromPut;

		if (Status.fromStatusCode(client.get(url).getStatus()) != Status.OK) {
			expectedStatusFromPut = Status.OK;
		} else {
			expectedStatusFromPut = Status.NO_CONTENT;
		}

		final Response putResponse = client.putWithHeaders(url, Entity.text(amount),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);

		assertThat(Status.fromStatusCode(putResponse.getStatus()),
			equalTo(expectedStatusFromPut)
		);

		TestFileOutput.outputJson(client.getEntity(url, String.class));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shouldVerifyAddedKeyInList(@ForAll("provideSignature") Signature signature,
		@ForAll ("providePubKey") String pubKey) {

		final String url = "/gridspork/validator/";
		final Response response = client.get(url + "/list");
		int originalNumMints = 0;
		int newMints = 0;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			final ValidatorSpork.SporkData data = response.readEntity(ValidatorSpork.class).getData();
			originalNumMints = data.getValidatorKeys().size();
		}
		final Response putResponse = client.putWithHeaders(url,
			Entity.text(pubKey), new MultivaluedHashMap(Map.of("privateKey",
			signature.getPrivateKey()))
		);

		if (Status.fromStatusCode(putResponse.getStatus()) == Status.OK) {
			newMints++;
		}

		if (newMints > 0) {
			final ValidatorSpork.SporkData data = client.getEntity(url, ValidatorSpork.class).getData();
			assertThat(data.getValidatorKeys().size(), equalTo(originalNumMints + newMints));
		}
	}
}
