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

package org.unigrid.hedgehog.model.network;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Capturing;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.ArbitraryGenerator;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.ledger.LedgerStreams;

public class NodeTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject
	private Topology topology;

	private enum Family {
		IP4, IP6
	}

	@Provide
	@SneakyThrows
	public Arbitrary<Node> provideNode(@ForAll Family family,
		@ForAll @IntRange(min = 1024, max = 65535) int port) {

		String address = switch (family) {
			case IP4 -> ArbitraryGenerator.ip4();
			case IP6 -> ArbitraryGenerator.ip6();
		};

		Node node;

		if (port % 3 == 0) {
			node = Node.fromAddress((family == Family.IP4 ? "%s" : "[%s]").formatted(address));
		} else {
			node = Node.fromAddress((family == Family.IP4 ? "%s:%d" : "[%s]:%d").formatted(address, port));
		}

		return Arbitraries.of(node);
	}

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@SneakyThrows
	@Example
	public void shouldRefuseAHostThatDoesNotResolve() {
		try {
			Node.fromAddress("no-such-host.invalid:52883");
			assertThat("A host that does not resolve should be refused", false);
		} catch (UnknownHostException ex) {
			assertThat(ex.getMessage(), containsString("no-such-host.invalid"));
		}
	}

	private static Node unresolved(String host) {
		return Node.builder().address(InetSocketAddress.createUnresolved(host, 52883)).build();
	}

	/* The identity of a node that cannot be resolved is its host name, so that it can still be hashed and compared */
	@Example
	public void shouldIdentifyANodeThatDoesNotResolveByItsHostName() {
		final Node node = unresolved("no-such-host.invalid");

		assertThat(node.getURI().toString(), equalTo("/no-such-host.invalid:52883"));
		assertThat(node.hashCode(), is(unresolved("no-such-host.invalid").hashCode()));
		assertThat(node, equalTo(unresolved("no-such-host.invalid")));
		assertThat(node, not(equalTo(unresolved("another-host.invalid"))));
	}

	/* Listing a node that does not resolve is allowed, keeping one is not: a peer could fill the topology with names */
	@Example
	public void shouldRefuseANodeThatDoesNotResolveInTheTopology() {
		final int before = topology.cloneNodes().size();

		assertThat(topology.addNode(unresolved("no-such-host.invalid")), is(false));
		assertThat(topology.cloneNodes().size(), is(before));
	}

	/* A host that cannot be resolved while the answer is read, as when a lookup fails once, must not stop the whole
	   list from being read */
	@Example
	@SneakyThrows
	public void shouldReadAListThatHoldsAHostThatDoesNotResolve() {
		final ObjectMapper mapper = new JsonConfiguration().getContext(Node.class);
		final String json = mapper.writeValueAsString(List.of(unresolved("no-such-host.invalid"),
			Node.fromAddress("127.0.0.1:52883")));
		final Set<Node> restored = mapper.readValue(json, new TypeReference<Set<Node>>() { });

		assertThat(restored, hasSize(2));
		assertThat(restored, hasItem(unresolved("no-such-host.invalid")));
	}

	@Property
	@SneakyThrows
	public void shouldBeAbleToCreateNodeFromAddress(@ForAll("provideNode") Node node) {
		assertThat(node, notNullValue());
	}

	@Property
	@SneakyThrows
	public void shouldKeepNodeIdentityThroughJson(@ForAll("provideNode") Node node) {
		final ObjectMapper mapper = new JsonConfiguration().getContext(Node.class);
		final Node restored = mapper.readValue(mapper.writeValueAsString(node), Node.class);

		assertThat(restored, equalTo(node));
		assertThat(restored.hashCode(), is(node.hashCode()));
	}

	@Property(tries = 100)
	public void shouldFilterIfMe(@ForAll("provideNode") Node node, @ForAll boolean me) {
		new Expectations(node) {{
			node.isMe(); result = me;
		}};

		final int originalSize = topology.cloneNodes().size();

		if (topology.containsNode(node) || me) {
			topology.addNode(node);
			assertThat(topology.cloneNodes().size(), is(originalSize));
		} else {
			topology.addNode(node);
			assertThat(topology.cloneNodes().size(), is(originalSize + 1));
		}
	}

	private static Node linkedBy(EmbeddedChannel dialed, EmbeddedChannel opened) {
		return Node.builder().address(new InetSocketAddress("127.0.100.1", 1000))
			.connection(Optional.of(LedgerStreams.connection(dialed)))
			.inbound(Optional.of(LedgerStreams.connection(opened))).build();
	}

	/* A connection that closed is dropped in the next round of dialing only, and until then the node is reached on the
	   stream that it opened; while both are open, on the connection that this node dialed */
	@Example
	public void shouldLinkToANodeByAnOpenStreamBeforeOneThatClosed() {
		final EmbeddedChannel dialed = new EmbeddedChannel();
		final EmbeddedChannel opened = new EmbeddedChannel();
		final Node node = linkedBy(dialed, opened);

		assertThat(node.link().map(Connection::getChannel), equalTo(Optional.of(dialed)));
		dialed.close();
		assertThat(node.link().map(Connection::getChannel), equalTo(Optional.of(opened)));
	}

	/* A node that dialed this one pays for what comes on that connection from the share of the peers it chose, and for
	   what comes on one that this node dialed from the share of anyone (PublishSporkChannelHandler), while the stream
	   that it opened is known as its own by its address and the port it said alone, which another one behind that
	   address can say as well: what is relayed goes on both while both are open, and on the one that is open else */
	@Example
	public void shouldRelayToANodeOnEveryLinkThatIsOpen() {
		final EmbeddedChannel dialed = new EmbeddedChannel();
		final EmbeddedChannel opened = new EmbeddedChannel();
		final EmbeddedChannel reopened = new EmbeddedChannel();
		final Node node = linkedBy(dialed, opened);
		final Packet packet = PublishGridnode.builder().build();

		Node.send(packet, node, Optional.empty());
		assertThat(dialed.<Packet>readOutbound(), is(packet));
		assertThat(opened.<Packet>readOutbound(), is(packet));

		opened.close();
		Node.send(packet, node, Optional.empty());
		assertThat(dialed.<Packet>readOutbound(), is(packet));

		dialed.close();
		node.setInbound(Optional.of(LedgerStreams.connection(reopened)));
		Node.send(packet, node, Optional.empty());
		assertThat(reopened.<Packet>readOutbound(), is(packet));
	}
}
