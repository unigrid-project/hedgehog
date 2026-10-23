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

package org.unigrid.hedgehog.service.ledger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.connection;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.stream;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.network.handler.SporkRecipients;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.producer.ApplicationDirectoryProducer;
import org.unigrid.hedgehog.model.producer.SporkDatabaseProducer;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkLag;
import org.unigrid.hedgehog.server.TestServer;

/* A node of an earlier release cannot read the sporks of the ledger, so no way that a spork leaves this node may reach
   a stream that did not settle on the ledger protocol: here the relay of a spork that came in, and in
   LedgerSporkGateRestTest a spork handed to the node over REST. The streams here are those of two nodes of the
   topology. */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class, PendingSporks.class,
	SporkLag.class, SporkDatabaseProducer.class, ApplicationDirectoryProducer.class }, scan = false)
public class SporkGateTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;
	@Mocked private ChannelHandlerContext context;

	@Inject private Topology topology;
	@Inject private PendingSporks pendingSporks;

	private final EmbeddedChannel ledger = stream(true);
	private final EmbeddedChannel earlier = stream(false);
	private Signature proposer;

	@BeforeTry
	@SneakyThrows
	public void before() {
		TestServer.mockProperties();
		topology.clear();
		pendingSporks.remove(Type.LEDGER_FEE);
		pendingSporks.remove(Type.MINT_SUPPLY);
		attach("10.9.9.1", ledger);
		attach("10.9.9.2", earlier);

		proposer = new Signature();

		final String cosigner = new Signature().getPublicKey();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner };
			}

			@Mock public String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};
	}

	@SneakyThrows
	private void attach(final String host, final EmbeddedChannel stream) {
		final Node node = Node.builder().address(new InetSocketAddress(host, 1000)).build();

		topology.addNode(node);
		topology.modifyNode(node, held -> held.setConnection(Optional.of(connection(stream))));
	}

	private static LedgerFee ledgerFee() {
		final LedgerFee spork = new LedgerFee();
		final LedgerFee.SporkData data = new LedgerFee.SporkData();

		data.setMinimumTransferFee(BigDecimal.valueOf(1, 3));
		spork.setData(data);
		return spork;
	}

	private static MintSupply mintSupply() {
		final MintSupply spork = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		return spork;
	}

	private GridSpork proposal(final GridSpork spork) throws Exception {
		spork.archive();
		spork.sign(proposer.getPrivateKey());
		return spork;
	}

	@Example
	public void shouldSendALedgerSporkToTheNodesOnTheLedgerProtocolOnly() {
		SporkRecipients.sendAll(ledgerFee(), topology);

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());
	}

	@Example
	public void shouldSendAnEarlierSporkToEveryNode() {
		SporkRecipients.sendAll(mintSupply(), topology);

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.MINT_SUPPLY));
		assertThat(sporkTypesWrittenTo(earlier), contains(Type.MINT_SUPPLY));
	}

	@Example
	@SneakyThrows
	public void shouldRelayALedgerSporkItTookInToTheNodesOnTheLedgerProtocolOnly() {
		new PublishSporkChannelHandler().typedChannelRead(context,
			PublishSpork.builder().gridSpork(proposal(ledgerFee())).build());

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.LEDGER_FEE));
		assertThat(sporkTypesWrittenTo(earlier), empty());
	}

	@Example
	@SneakyThrows
	public void shouldRelayAnEarlierSporkItTookInToEveryNode() {
		new PublishSporkChannelHandler().typedChannelRead(context,
			PublishSpork.builder().gridSpork(proposal(mintSupply())).build());

		assertThat(sporkTypesWrittenTo(ledger), contains(Type.MINT_SUPPLY));
		assertThat(sporkTypesWrittenTo(earlier), contains(Type.MINT_SUPPLY));
	}
}
