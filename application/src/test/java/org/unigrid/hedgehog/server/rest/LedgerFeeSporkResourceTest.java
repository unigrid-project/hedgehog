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
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.LedgerFeeSporkTest;

/* A fee that the spork refuses cannot be signed, as its wire form cannot hold it: the command line refuses it first
   (LedgerFeeSporkCommandTest), and a node refuses one past the cap of the wire (GridSporkWireResourceTest) */
public class LedgerFeeSporkResourceTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/ledger-fee";

	@Provide
	public Arbitrary<LedgerFee.SporkData> validData() {
		return LedgerFeeSporkTest.fees().map(LedgerFeeSporkTest::with);
	}

	private Optional<BigDecimal> stored() throws ResponseOddityException {
		final Response response = client.get(URL);

		if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
			return Optional.empty();
		}

		return Optional.of(response.readEntity(LedgerFee.class).<LedgerFee.SporkData>getData()
			.getMinimumTransferFee());
	}

	@SneakyThrows
	@Property(tries = 10)
	public void storesAnyFeeUpToACoin(@ForAll("provideSignature") final Signature signature,
		@ForAll("validData") final LedgerFee.SporkData data) {

		final Response proposal = propose(Type.LEDGER_FEE, (LedgerFee.SporkData held) -> {
			held.setMinimumTransferFee(data.getMinimumTransferFee());
			held.setMinimumNewAccountAmount(data.getMinimumNewAccountAmount());
		});

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(stored().orElseThrow(), comparesEqualTo(data.getMinimumTransferFee()));
	}

	@SneakyThrows
	@Property(tries = 2)
	public void rejectsAFeeThatNoBoardKeySigned(@ForAll("provideSignature") final Signature trusted,
		@ForAll("validData") final LedgerFee.SporkData data) {

		final Optional<BigDecimal> before = stored();

		assertThat(Status.fromStatusCode(submit(next(Type.LEDGER_FEE, (LedgerFee.SporkData held) ->
			held.setMinimumTransferFee(data.getMinimumTransferFee()), STRANGER)).getStatus()),
			equalTo(Status.UNAUTHORIZED));
		assertThat(stored(), equalTo(before));
	}
}
