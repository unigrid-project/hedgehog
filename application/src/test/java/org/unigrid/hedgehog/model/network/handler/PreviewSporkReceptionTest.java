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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.connection;
import static org.unigrid.hedgehog.service.ledger.LedgerStreams.sporkTypesWrittenTo;
import org.unigrid.hedgehog.model.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.producer.ApplicationDirectoryProducer;
import org.unigrid.hedgehog.model.producer.SporkDatabaseProducer;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

/* A node run without --preview drops a spork of a part that this release leaves off once it has read it: it does not
   store it, hold it, relay it or note it as a sign that the node is behind. The peer here takes chain traffic, so it
   would be sent the communication spork by a node that took it. */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class, PendingSporks.class,
	SporkLag.class, SporkDatabaseProducer.class, ApplicationDirectoryProducer.class }, scan = false)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class PreviewSporkReceptionTest extends BaseMockedWeldTest {
	private static final List<String> DAEMON = List.of("daemon");
	private static final String[] PROXY_NODE = { Network.PROXY_PROTOCOL, Network.SPORK_PROTOCOL, PREVIOUS_RELEASE[0],
		PREVIOUS_RELEASE[1] };

	@Mocked private ChannelHandlerContext context;

	@Inject private Topology topology;
	@Inject private PendingSporks pendingSporks;
	@Inject private SporkDatabase database;

	private final List<Type> noted = new CopyOnWriteArrayList<>();
	private QuicLoopback loopback;
	private EmbeddedChannel peer;
	private Signature proposer;
	private Signature cosigner;

	@BeforeTry
	@SneakyThrows
	public void before() {
		topology.clear();
		noted.clear();

		for (final Type type : List.of(Type.COMMUNICATION, Type.MINT_SUPPLY)) {
			pendingSporks.remove(type);
			database.clear(type);
		}

		loopback = QuicLoopback.between(PROXY_NODE, PROXY_NODE);
		peer = new EmbeddedChannel(loopback.clientEnd(), DefaultChannelId.newInstance(), true, false);
		attach("10.9.9.1", peer);

		proposer = new Signature();
		cosigner = new Signature();

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}
		};

		new MockUp<SporkLag>() {
			@Mock public void record(GridSpork spork, SporkDatabase db, SporkLag.Turn turn) {
				noted.add(spork.getType());
			}
		};
	}

	@AfterTry
	@SneakyThrows
	public void after() {
		loopback.close();
	}

	@SneakyThrows
	private void attach(final String host, final EmbeddedChannel stream) {
		final Node node = Node.builder().address(new InetSocketAddress(host, 1000)).build();

		topology.addNode(node);
		topology.modifyNode(node, held -> held.setConnection(Optional.of(connection(stream))));
	}

	@SneakyThrows
	private <T extends GridSpork> T cosigned(final T spork) {
		spork.archive();
		spork.sign(proposer.getPrivateKey());
		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	private static MintSupply mintSupply() {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		return spork;
	}

	@SneakyThrows
	private void receive(final GridSpork spork) {
		new PublishSporkChannelHandler().typedChannelRead(context, PublishSpork.builder().gridSpork(spork).build());
	}

	@Example
	public void shouldNeitherStoreRelayNorNoteACommunicationSporkWithoutThePreview() {
		receive(cosigned(new CommunicationSpork()));

		assertThat(database.get(Type.COMMUNICATION), nullValue());
		assertThat(pendingSporks.list(), empty());
		assertThat(sporkTypesWrittenTo(peer), empty());
		assertThat(noted, empty());
	}

	@Example
	public void shouldStoreRelayAndNoteASporkOfThisReleaseWithoutThePreview() {
		final MintSupply spork = cosigned(mintSupply());

		receive(spork);

		assertThat(database.get(Type.MINT_SUPPLY), equalTo(spork));
		assertThat(sporkTypesWrittenTo(peer), contains(Type.MINT_SUPPLY));
		assertThat(noted, contains(Type.MINT_SUPPLY));
	}

	@Example
	public void shouldStoreRelayAndNoteACommunicationSporkWithThePreview() {
		final CommunicationSpork spork = cosigned(new CommunicationSpork());

		OptionsCli.parse(DAEMON, "--preview");
		receive(spork);

		assertThat(database.get(Type.COMMUNICATION), equalTo(spork));
		assertThat(sporkTypesWrittenTo(peer), contains(Type.COMMUNICATION));
		assertThat(noted, contains(Type.COMMUNICATION));
	}
}
