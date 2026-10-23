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

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
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
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

public class VestingStorageResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/vesting-storage/";

	@Provide
	public Arbitrary<Vesting> provideVesting(@ForAll @BigRange(min = "1", max = "1000000") @Scale(8) BigDecimal amount,
		@ForAll @ByteRange(min = 1) byte parts, @ForAll @DurationRange(min = "P1D", max = "P1000D") Duration duration,
		@ForAll @InstantRange() Instant start, @ForAll @ByteRange(min = 1) int cliff,
		@ForAll @ByteRange(min = 1, max = Byte.MAX_VALUE) int percent,
		@ForAll int block) {

		return Arbitraries.of(Vesting.builder().amount(amount).duration(duration)
			.parts(parts).start(start).cliff(cliff).percent(percent).block(block).build());
	}

	/* Map keys travel through JSON as their toString(), so each key read back wraps the address it stood for */
	private static Set<String> wifsOf(VestingStorage.SporkData data) {
		return data.getVestingAddresses().keySet().stream().map(Address::getWif).collect(Collectors.toSet());
	}

	private Response vest(String address, Vesting vesting) {
		return propose(Type.VESTING_STORAGE, (VestingStorage.SporkData data) -> data.getVestingAddresses()
			.put(new Address(address), vesting));
	}

	/* Each proposal carries the vestings of the one before it, so one co-signature stores them all */
	@SneakyThrows
	@Property(tries = 5)
	public void shoulBeVerifiableInList(@ForAll("provideSignature") Signature signature,
		@ForAll @Size(max = 5) @UniqueElements List<@AlphaChars @StringLength(36) String> addresses,
		@ForAll @UniqueElements @Size(5) List<@From("provideVesting") Vesting> vests) {

		final Response response = client.get(URL);
		final Set<String> expectedAddresses = new HashSet<>();
		Response lastProposal = null;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			expectedAddresses.addAll(wifsOf(response.readEntity(VestingStorage.class).getData()));
		}

		for (int i = 0; i < addresses.size(); i++) {
			final Response proposal = vest(addresses.get(i), vests.get(i));

			if (Status.fromStatusCode(proposal.getStatus()) == Status.ACCEPTED) {
				expectedAddresses.add(Address.builder().wif(addresses.get(i)).build().toString());
				lastProposal = proposal;
			}
		}

		if (Objects.nonNull(lastProposal)) {
			assertThat(Status.fromStatusCode(cosign(lastProposal).getStatus()), equalTo(Status.OK));
			assertThat(wifsOf(client.getEntity(URL, VestingStorage.class).getData()), equalTo(expectedAddresses));
		}
	}

	@SneakyThrows
	@Property(tries = 10)
	public void shoulBeAbleToGetVestingStorageSpork(@ForAll("provideSignature") Signature signature,
		@ForAll("provideVesting") Vesting vesting, @ForAll @AlphaChars @StringLength(36) String address) {

		final Response proposal = vest(address, vesting);

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(client.getEntity(URL + address, Vesting.class).getAmount(), comparesEqualTo(vesting.getAmount()));

		TestFileOutput.outputJson(client.getEntity(URL + address, String.class));
	}

	/* A vesting without an amount can be signed, its wire form has room for none, but no node takes it */
	@Property(tries = 1)
	public void refusesAVestingThatNoNodeCanActOn(@ForAll("provideSignature") Signature signature,
		@ForAll("provideVesting") Vesting vesting, @ForAll @AlphaChars @StringLength(36) String address) {

		final ResponseOddityException refusal = assertThrows(ResponseOddityException.class,
			() -> vest(address, vesting.toBuilder().amount(null).build()));

		assertThat(refusal.getMessage(), startsWith("400 "));
		assertThat(refusal.getBody(), containsString(address));
	}
}
