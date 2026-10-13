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

package org.unigrid.hedgehog.service.chain;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.NodeConnections;

public class NettyChainTransportTest {
	private static final List<Gridnode> GRIDNODES = List.of(
		Gridnode.builder().id("alpha").hostName("10.0.0.1:52883").build(),
		Gridnode.builder().id("bravo").hostName("10.0.0.2:52883").build(),
		Gridnode.builder().id("charlie").build());

	private static ChainTransport transportOver(final Node... nodes) {
		return new NettyChainTransport(() -> Set.of(nodes), () -> GRIDNODES);
	}

	@Example
	public void reachesAGridnodeThroughTheChainStreamOfTheConnectionToItsAddress() {
		final EmbeddedChannel alpha = new EmbeddedChannel();
		final EmbeddedChannel bravo = new EmbeddedChannel();
		final ChainTransport transport = transportOver(
			NodeConnections.connected("10.0.0.1:52883", new EmbeddedChannel(), alpha),
			NodeConnections.connected("10.0.0.2:52883", new EmbeddedChannel(), bravo));

		assertThat(transport.streamTo("alpha"), equalTo(Optional.of(alpha)));
		assertThat(transport.streamTo("bravo"), equalTo(Optional.of(bravo)));
	}

	@Example
	public void hasNoStreamToAGridnodeWhoseConnectionHasNoChainStreamYet() {
		final ChainTransport transport = transportOver(NodeConnections.connected("10.0.0.1:52883",
			new EmbeddedChannel()));

		assertThat(transport.streamTo("alpha"), equalTo(Optional.empty()));
	}

	@Example
	public void hasNoStreamToAGridnodeWhoseChainStreamHasClosed() {
		final EmbeddedChannel chain = new EmbeddedChannel();
		final ChainTransport transport = transportOver(NodeConnections.connected("10.0.0.1:52883",
			new EmbeddedChannel(), chain));

		assertThat(transport.streamTo("alpha"), equalTo(Optional.of(chain)));
		chain.close();
		assertThat(transport.streamTo("alpha"), equalTo(Optional.empty()));
	}

	@Example
	public void hasNoStreamToAGridnodeItDoesNotKnowOrIsNotConnectedTo() {
		final ChainTransport transport = transportOver(NodeConnections.unconnected("10.0.0.1:52883"),
			NodeConnections.connected("10.0.0.9:52883", new EmbeddedChannel()));

		assertThat(transport.streamTo("alpha"), equalTo(Optional.empty()));
		assertThat(transport.streamTo("bravo"), equalTo(Optional.empty()));
		assertThat(transport.streamTo("charlie"), equalTo(Optional.empty()));
		assertThat(transport.streamTo("delta"), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheTopologyAgainOnEveryLookup() {
		final EmbeddedChannel alpha = new EmbeddedChannel();
		final AtomicReference<Set<Node>> nodes = new AtomicReference<>(Set.of());
		final ChainTransport transport = new NettyChainTransport(nodes::get, () -> GRIDNODES);

		assertThat(transport.streamTo("alpha"), equalTo(Optional.empty()));

		nodes.set(Set.of(NodeConnections.connected("10.0.0.1:52883", new EmbeddedChannel(), alpha)));
		assertThat(transport.streamTo("alpha"), equalTo(Optional.of(alpha)));

		nodes.set(Set.of());
		assertThat(transport.streamTo("alpha"), equalTo(Optional.empty()));
	}

	@Example
	public void findsAGridnodeBesideOneWithoutAnId() {
		final EmbeddedChannel alpha = new EmbeddedChannel();
		final List<Gridnode> gridnodes = List.of(Gridnode.builder().hostName("10.0.0.3:52883").build(),
			GRIDNODES.get(0));
		final ChainTransport transport = new NettyChainTransport(() -> Set.of(
			NodeConnections.connected("10.0.0.1:52883", new EmbeddedChannel(), alpha)), () -> gridnodes);

		assertThat(transport.streamTo("alpha"), equalTo(Optional.of(alpha)));
	}
}
