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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.unigrid.hedgehog.model.Address;
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

	/* Map keys travel through JSON as their toString(), so each key read back wraps the address it stood for */
	private static Set<String> wifsOf(VestingStorage.SporkData data) {
		return data.getVestingAddresses().keySet().stream().map(Address::getWif).collect(Collectors.toSet());
	}

	@SneakyThrows
	@Property(tries = 30)
	public void shoulBeVerifiableInList(@ForAll("provideSignature") Signature signature,
		@ForAll @Size(max = 5) @UniqueElements List<@AlphaChars @StringLength(36) String> addresses,
		@ForAll @UniqueElements @Size(5) List<@From("provideVesting") Vesting> vests) {

		final String url = "/gridspork/vesting-storage/";
		final Response response = client.get(url);
		final Set<String> expectedAddresses = new HashSet<>();
		Response lastProposal = null;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			final VestingStorage.SporkData data = response.readEntity(VestingStorage.class).getData();
			expectedAddresses.addAll(wifsOf(data));
		}

		for (int i = 0; i < addresses.size(); i++) {
			final Response putResponse = client.putWithHeaders(url + addresses.get(i),
				Entity.json(vests.get(i)),
				new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
			);

			if (Status.fromStatusCode(putResponse.getStatus()) == Status.ACCEPTED) {
				expectedAddresses.add(Address.builder().wif(addresses.get(i)).build().toString());
				lastProposal = putResponse;
			}
		}

		if (Objects.nonNull(lastProposal)) {
			assertThat(Status.fromStatusCode(cosign(lastProposal).getStatus()), equalTo(Status.OK));

			final VestingStorage.SporkData data = client.getEntity(url, VestingStorage.class).getData();
			assertThat(wifsOf(data), equalTo(expectedAddresses));
		}
	}

	@SneakyThrows
	@Property(tries = 50)
	public void shoulBeAbleToGetVestingStorageSpork(@ForAll("provideSignature") Signature signature,
		@ForAll("provideVesting") Vesting vesting, @ForAll @AlphaChars @StringLength(36) String address) {

		final String url = "/gridspork/vesting-storage/%s".formatted(address);
		final Response putResponse = client.putWithHeaders(url, Entity.json(vesting),
			new MultivaluedHashMap(Map.of("privateKey", signature.getPrivateKey()))
		);

		assertThat(Status.fromStatusCode(putResponse.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(putResponse).getStatus()), equalTo(Status.OK));

		TestFileOutput.outputJson(client.getEntity(url, String.class));
	}
}
