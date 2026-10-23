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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.Positive;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.UniqueElements;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

public class MintStorageResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/mint-storage/";

	/* An address that a node can read: the amounts of the other kinds are refused when they are proposed */
	@Provide
	public Arbitrary<Location> provideLocation(@ForAll @Size(20) byte[] hash, @ForAll @Positive int height) {
		return Arbitraries.of(Location.builder().height(height).address(
			Address.builder().wif(LegacyAddress.encode(hash)).build()
		).build());
	}

	private Response grow(Location location, BigDecimal amount) {
		return propose(Type.MINT_STORAGE, (MintStorage.SporkData data) -> data.getMints().put(location, amount));
	}

	/* Each proposal carries the mints of the one before it, so one co-signature stores them all */
	@SneakyThrows
	@Property(tries = 5, shrinking = ShrinkingMode.OFF)
	public void shoulBeVerifiableInList(@ForAll("provideSignature") Signature signature,
		@ForAll @UniqueElements @Size(5) List<@From("provideLocation") Location> locations,
		@ForAll @BigRange(min = "1", max = "1000000") BigDecimal amount) {

		final Response response = client.get(URL);
		final Set<Location> expectedLocations = new HashSet<>();
		Response lastProposal = null;

		if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
			final MintStorage.SporkData data = response.readEntity(MintStorage.class).getData();
			expectedLocations.addAll(data.getMints().keySet());
		}

		for (Location l : locations) {
			final Response proposal = grow(l, amount);

			if (Status.fromStatusCode(proposal.getStatus()) == Status.ACCEPTED) {
				expectedLocations.add(l);
				lastProposal = proposal;
			}
		}

		if (Objects.nonNull(lastProposal)) {
			assertThat(Status.fromStatusCode(cosign(lastProposal).getStatus()), equalTo(Status.OK));

			final MintStorage.SporkData data = client.getEntity(URL, MintStorage.class).getData();
			assertThat(data.getMints().keySet(), equalTo(expectedLocations));
		}
	}

	@SneakyThrows
	@Property(tries = 10)
	public void shoulBeAbleToGetMintStorageSpork(@ForAll("provideSignature") Signature signature,
		@ForAll("provideLocation") Location location, @ForAll @BigRange(min = "1", max = "1000000") BigDecimal amount) {

		final String url = URL + "%s/%d".formatted(location.getAddress().getWif(), location.getHeight());
		final Response proposal = grow(location, amount);

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(new BigDecimal(client.getEntity(url, String.class)), comparesEqualTo(amount));

		TestFileOutput.outputJson(client.getEntity(url, String.class));
	}
}
