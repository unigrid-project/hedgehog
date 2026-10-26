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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

public class CommunicationSporkResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/communication";

	@Inject
	private SporkDatabase sporkDatabase;

	@Provide
	public Arbitrary<SporkData> validData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(CommunicationSporkTest::accepts);
	}

	@Provide
	public Arbitrary<SporkData> invalidData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(data -> !CommunicationSporkTest.accepts(data));
	}

	private Optional<SporkData> stored() throws ResponseOddityException {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
			return Optional.empty();
		}

		return Optional.of(response.readEntity(CommunicationSpork.class).<SporkData>getData());
	}

	private static void replace(final SporkData held, final SporkData data) {
		held.setGroupSize(data.getGroupSize());
		held.setReorganizePeriodSeconds(data.getReorganizePeriodSeconds());
		held.setChainsPerGroup(data.getChainsPerGroup());
		held.setClientsPerChain(data.getClientsPerChain());
		held.setChainCapacityBytes(data.getChainCapacityBytes());
		held.setRetentionSeconds(data.getRetentionSeconds());
		held.setMaxEntryBytes(data.getMaxEntryBytes());
		held.setSessionIdleSeconds(data.getSessionIdleSeconds());
		held.setCommunicationFlags(data.getCommunicationFlags());
	}

	@SneakyThrows
	@Property(tries = 5)
	public void answersNothingWhileNoSporkIsSet() {
		sporkDatabase.setCommunicationSpork(null);

		assertThat(stored(), equalTo(Optional.empty()));
	}

	@SneakyThrows
	@Property(tries = 10)
	public void storesAnyParametersWithinTheBoundsOnceTwoBoardKeysSigned(
		@ForAll("provideSignature") final Signature signature, @ForAll("validData") final SporkData data) {

		final Response proposal = propose(Type.COMMUNICATION, (SporkData held) -> replace(held, data));

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(stored(), equalTo(Optional.of(data)));
	}

	@SneakyThrows
	@Property(tries = 2)
	public void rejectsParametersThatNoBoardKeySigned(@ForAll("provideSignature") final Signature trusted,
		@ForAll("validData") final SporkData data) {

		final Optional<SporkData> before = stored();

		assertThat(Status.fromStatusCode(submit(next(Type.COMMUNICATION, (SporkData held) -> replace(held, data),
			STRANGER)).getStatus()), equalTo(Status.UNAUTHORIZED));
		assertThat(stored(), equalTo(before));
	}

	@SneakyThrows
	@Property(tries = 5)
	public void refusesParametersOutsideTheBoundsEvenWhenABoardKeySignedThem(
		@ForAll("provideSignature") final Signature signature, @ForAll("invalidData") final SporkData data) {

		final Optional<SporkData> before = stored();

		assertThat(assertThrows(ResponseOddityException.class, () -> submit(next(Type.COMMUNICATION,
			(SporkData held) -> replace(held, data), PROPOSER))).getMessage(), containsString("400"));
		assertThat(stored(), equalTo(before));
	}
}
