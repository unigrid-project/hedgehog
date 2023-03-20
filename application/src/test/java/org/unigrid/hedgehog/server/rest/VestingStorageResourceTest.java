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

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.constraints.Scale;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.time.api.constraints.DurationRange;
import net.jqwik.time.api.constraints.InstantRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.crypto.Signature;
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
	@Property(tries = 50)
	public void shoulBeAbleToGetVestingStorageSpork(@ForAll("provideSignature") Signature signature,
		@ForAll("provideVesting") Vesting vesting, @ForAll @AlphaChars @StringLength(36) String address) {

		Status expectedStatusFromPut;

		final RestClient client = new RestClient(server.getRest().getHostName(),
			server.getRest().getPort(), true
		);

		if (Status.fromStatusCode(client.get("/gridspork/vesting-storage/"+ address).getStatus()) != Status.OK) {
			expectedStatusFromPut = Status.OK;
		} else {
			expectedStatusFromPut = Status.NO_CONTENT;
		}

		final Response putResponse = client.putWithHeaders("/gridspork/vesting-storage/" + address, vesting,
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);

		assertThat(Status.fromStatusCode(putResponse.getStatus()),
			equalTo(expectedStatusFromPut)
		);

		TestFileOutput.outputJson(client.getEntity("/gridspork/vesting-storage/" + address, String.class));
		client.close();
	}
}
