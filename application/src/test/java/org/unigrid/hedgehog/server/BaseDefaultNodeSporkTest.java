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

package org.unigrid.hedgehog.server;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.connection;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.client.rest.SporkClient;
import org.unigrid.hedgehog.model.InMemoryDataDirectoryMockUp;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PeerProtocol;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.SporkWire;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;
import org.unigrid.hedgehog.model.spork.content.SporkContent;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;

/* A default node whose board is two keys of the test, so that it takes the sporks they sign, with its data directory
   in memory. One peer of its topology settled on the ledger protocol over a real QUIC connection, which reads every
   type of spork, so that the only reason the node sends it a spork of the preview is that this release does not take
   it. */
public class BaseDefaultNodeSporkTest extends BaseDefaultNodeTest {
	protected static final List<Type> OF_THE_PREVIEW = List.of(Type.STORAGE, Type.LEDGER_BLACKLIST, Type.LEDGER_FEE,
		Type.COMMUNICATION);

	/* The ledger protocol comes first, so it is the one that both ends settle on */
	private static final String[] EVERY_PROTOCOL = { Network.LEDGER_PROTOCOL, Network.PROXY_PROTOCOL,
		Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0], PREVIOUS_RELEASE[1] };

	/* A P-521 key pair takes a dozen tries to generate, so the two keys of the board are made once for the run */
	private static final Signature PROPOSER = keyPair();
	private static final Signature COSIGNER = keyPair();

	protected static InMemoryDataDirectoryMockUp dataDirectory;

	@Inject protected Topology topology;
	@Inject protected PendingSporks pendingSporks;
	@Inject protected SporkDatabase database;
	@Inject protected SporkLag lag;

	/* What the node writes to the peer lands here, to be read by the test */
	protected EmbeddedChannel recipient;
	protected RestClient client;
	private QuicLoopback loopback;

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	@BeforeContainer
	private static void holdTheBoard() {
		dataDirectory = new InMemoryDataDirectoryMockUp();

		new MockUp<NetworkKey>() {
			@Mock public /* static */ String[] getPublicKeys() {
				return new String[] { PROPOSER.getPublicKey(), COSIGNER.getPublicKey() };
			}
		};
	}

	@BeforeTry
	@SneakyThrows
	public void connectAPeerThatReadsEverySporkType() {
		topology.clear();
		loopback = QuicLoopback.between(EVERY_PROTOCOL, EVERY_PROTOCOL);
		recipient = new EmbeddedChannel(loopback.clientEnd(), DefaultChannelId.newInstance(), true, false);

		assertThat("settled on the ledger protocol", LedgerProtocol.negotiatedOn(recipient), is(true));
		assertThat("takes the chain traffic", PeerProtocol.speaksProxy(recipient), is(true));

		final Node node = Node.builder().address(new InetSocketAddress("10.9.9.1", 1000)).build();

		topology.addNode(node);
		topology.modifyNode(node, held -> held.setConnection(Optional.of(connection(recipient))));
		client = new RestClient(RestOptions.getHost(), RestOptions.getPort(), true, RestOptions.getToken());
	}

	@AfterTry
	@SneakyThrows
	public void disconnectThePeer() {
		client.close();
		topology.clear();
		recipient.close();
		loopback.close();
	}

	/* Signed by the proposer alone, as a board member signs a spork before the second one co-signs it */
	@SneakyThrows
	protected static GridSpork proposed(final GridSpork spork) {
		spork.archive();
		spork.sign(PROPOSER.getPrivateKey());
		return spork;
	}

	@SneakyThrows
	protected static GridSpork cosigned(final GridSpork spork) {
		proposed(spork).cosign(COSIGNER.getPrivateKey());
		return spork;
	}

	/* The spork of a type as a node that takes the type would hold it: two board keys signed it, and its content is one
	   that such a node accepts */
	protected static GridSpork cosigned(final Type type) {
		final GridSpork spork = cosigned(GridSpork.create(type));

		SporkContent.validate(spork, null);
		assertThat(type + " is signed by two board keys", spork.isDoublySigned(), is(true));
		return spork;
	}

	protected static GridSpork proposed(final Type type) {
		final GridSpork spork = proposed(GridSpork.create(type));

		SporkContent.validate(spork, null);
		assertThat(type + " is signed by a board key", spork.isValidSignature(), is(true));
		return spork;
	}

	/* A spork of this release, which the node takes, to show that it takes a spork signed in the same way */
	protected static GridSpork mintSupply() {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		return cosigned(spork);
	}

	/* A node that takes the type notes a spork like this one as a sign that the network has moved on, as its database
	   holds none of the type. So the lag is a witness of whether the node took the spork. */
	protected void assertWouldMakeANodeBehind(final GridSpork spork) {
		final SporkLag other = new SporkLag();

		other.record(spork, database, "10.9.9.9");
		assertThat(spork.getType() + " would put a node behind", other.isBehind(spork.getType(), database), is(true));
	}

	/* A node that holds none of the sporks that these examples hand it, and no proposal of one */
	protected void forgetTheSporks() {
		Stream.concat(OF_THE_PREVIEW.stream(), Stream.of(Type.MINT_SUPPLY)).forEach(type -> {
			pendingSporks.remove(type);
			database.clear(type);
		});
	}

	/* What the node holds, relays and notes after sporks of the preview were handed to it. The peer took them all if
	   the node had sent them. */
	protected void assertNoSporkOfThePreviewTaken() {
		for (Type type : OF_THE_PREVIEW) {
			assertThat(type + " stored", database.get(type), is(nullValue()));
			assertThat(type + " held as a proposal", pendingSporks.proposalOf(type), is(Optional.empty()));
			assertThat(type + " noted as a sign of being behind", lag.isBehind(type, database), is(false));
		}

		assertThat("proposals held", pendingSporks.list(), is(empty()));
		assertThat("sporks sent to the peer", sporkTypesWrittenTo(recipient), is(empty()));
		assertThat("parts of the preview created", createdOfThePreview(), is(empty()));
	}

	/* A spork of this release is stored, and sent on to the peer, as a spork is that the node takes */
	protected void assertTakenAndSentOn(final GridSpork spork) {
		assertThat(database.get(spork.getType()).getSignature(), equalTo(spork.getSignature()));
		assertThat(sporkTypesWrittenTo(recipient), contains(spork.getType()));
	}

	@SneakyThrows
	protected Response submit(final GridSpork spork) {
		return client.post(SporkClient.WIRE, Entity.entity(SporkWire.encode(spork), MediaType.APPLICATION_OCTET_STREAM));
	}
}
