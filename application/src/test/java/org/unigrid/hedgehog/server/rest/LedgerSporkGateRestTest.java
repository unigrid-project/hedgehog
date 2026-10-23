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

import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.connection;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.stream;

/* A node of an earlier release cannot read the sporks of the ledger: every step of a proposal that is handed to the
   node, the proposal, its co-signature and a renewal, goes to the nodes on the ledger protocol only */
public class LedgerSporkGateRestTest extends BaseRestClientTest {
	@Inject private Topology topology;

	@SneakyThrows
	private static void attach(final Topology held, final String host, final EmbeddedChannel stream) {
		final Node node = Node.builder().address(new InetSocketAddress(host, 1000)).build();

		held.addNode(node);
		held.modifyNode(node, member -> member.setConnection(Optional.of(connection(stream))));
	}

	@SneakyThrows
	@Property(tries = 1)
	public void shouldSendAProposalItsCosignatureAndItsRenewalToTheNodesOnTheLedgerProtocolOnly(
		@ForAll("provideSignature") final Signature signature) {

		final EmbeddedChannel ledger = stream(true);
		final EmbeddedChannel earlier = stream(false);

		attach(topology, "10.9.9.1", ledger);
		attach(topology, "10.9.9.2", earlier);

		final Response proposal = propose(Type.LEDGER_FEE, (LedgerFee.SporkData data) ->
			data.setMinimumTransferFee(BigDecimal.valueOf(1, 3)));

		assertThat(Status.fromStatusCode(proposal.getStatus()), equalTo(Status.ACCEPTED));
		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());

		assertThat(Status.fromStatusCode(cosign(proposal).getStatus()), equalTo(Status.OK));
		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());

		assertThat(Status.fromStatusCode(renew(Type.LEDGER_FEE, signature).getStatus()), equalTo(Status.ACCEPTED));
		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());
	}
}
