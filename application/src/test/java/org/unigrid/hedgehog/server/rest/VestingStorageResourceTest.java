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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.constraints.UniqueElements;
import net.jqwik.time.api.constraints.DurationRange;
import net.jqwik.time.api.constraints.InstantRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

public class VestingStorageResourceTest extends BaseRestClientTest {
	@Provide
	public Arbitrary<Vesting> provideVesting(@ForAll @BigRange(min = "1", max = "1000000") @Scale(12) BigDecimal amount,
		@ForAll @ByteRange(min = 1) byte parts, @ForAll @DurationRange(min = "P1D", max = "P1000D") Duration duration,
		@ForAll @InstantRange() Instant start) {

		return Arbitraries.of(Vesting.builder().amount(amount).duration(duration)
			.parts(parts).start(start).build());
	}

	@SneakyThrows
	@Property(tries = 30)
	public void shoulBeVerifiableInList(@ForAll("provideSignature") Signature signature,
		@ForAll @Size(max = 5) @UniqueElements List<@AlphaChars @StringLength(36) String> addresses,
		@ForAll @UniqueElements @Size(5) List<@From("provideVesting") Vesting> vests) {

		final String url = "/gridspork/vesting-storage/";
		final Response response = client.get(url);
		int originalNumVests = 0;
		int newVests = 0;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			final VestingStorage.SporkData data = response.readEntity(VestingStorage.class).getData();
			originalNumVests = data.getVestingAddresses().size();
		}

		for (int i = 0; i < addresses.size(); i++) {
			final Response putResponse = client.putWithHeaders(url + addresses.get(i),
				Entity.json(vests.get(i)),
				new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
			);

			if (Status.fromStatusCode(putResponse.getStatus()) == Status.OK) {
				newVests++;
			}
		}

		if (newVests > 0) {
			final VestingStorage.SporkData data = client.getEntity(url, VestingStorage.class).getData();
			assertThat(data.getVestingAddresses().size(), equalTo(originalNumVests + newVests));
		}
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shoulBeAbleToGetVestingStorageSpork(@ForAll("provideSignature") Signature signature,
		@ForAll("provideVesting") Vesting vesting, @ForAll @AlphaChars @StringLength(36) String address) {

		final String url = "/gridspork/vesting-storage/%s".formatted(address);
		Status expectedStatusFromPut;

		if (Status.fromStatusCode(client.get(url).getStatus()) == Status.OK) {
			expectedStatusFromPut = Status.NO_CONTENT;
		} else {
			expectedStatusFromPut = Status.OK;
		}

		final Response putResponse = client.putWithHeaders(url, Entity.json(vesting),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);

		assertThat(Status.fromStatusCode(putResponse.getStatus()),
			equalTo(expectedStatusFromPut)
		);

		TestFileOutput.outputJson(client.getEntity(url, String.class));
	}
}
