/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

public class GridnodeChannelsTest {
	private static final String ADDRESS = "10.0.0.1:52883";
	private static final String OTHER_ADDRESS = "10.0.0.2:52883";
	private static final Gridnode TARGET = Gridnode.builder().id("a").hostName(ADDRESS).build();

	private static Optional<?> streamTo(final Gridnode gridnode, final Node... nodes) {
		return new GridnodeChannels(() -> Set.of(nodes)).of(gridnode);
	}

	/* Unlike a hash set it holds nodes that cannot be hashed and keeps their order */
	private static Set<Node> listed(final Node... nodes) {
		return new AbstractSet<>() {
			@Override public Iterator<Node> iterator() {
				return Arrays.asList(nodes).iterator();
			}

			@Override public int size() {
				return nodes.length;
			}
		};
	}

	private static Optional<?> streamIn(final Gridnode gridnode, final Set<Node> nodes) {
		return new GridnodeChannels(() -> nodes).of(gridnode);
	}

	private static Gridnode at(final String hostName) {
		return Gridnode.builder().id("a").hostName(hostName).build();
	}

	private static Node unresolved() {
		return Node.builder().address(InetSocketAddress.createUnresolved("nohost.invalid", 52883)).build();
	}

	/* Every kind is a node the lookup has to pass over, none of them is at the address of the target */
	private static Node otherNode(final int kind) {
		final Node node = NodeConnections.unconnected(OTHER_ADDRESS);

		switch (kind) {
			case 0:
				return null;
			case 1:
				return Node.builder().build();
			case 2:
				return unresolved();
			case 3:
				node.setConnection(null);
				return node;
			case 4:
				return NodeConnections.connected(OTHER_ADDRESS, null);
			case 5:
				return node;
			default:
				return NodeConnections.connected(OTHER_ADDRESS, new EmbeddedChannel());
		}
	}

	@Provide
	public Arbitrary<String> unusableHostNames() {
		return Arbitraries.of("", " ", ":", "::", "[", "[::1", "a b:1", "10.0.0.1:port", "10.0.0.1:99999",
			"10.0.0.1:-1", ":52883", "@", "10.0.0.1:52883:1", "nohost.invalid:52883");
	}

	@Example
	public void findsTheStreamOfTheNodeAtTheAddressOfTheGridnode() {
		final EmbeddedChannel channel = new EmbeddedChannel();

		assertThat(streamTo(TARGET, NodeConnections.connected("10.0.0.2:52883", new EmbeddedChannel()),
			NodeConnections.connected(ADDRESS, channel)), equalTo(Optional.of(channel)));
	}

	@Example
	public void hasNoStreamWithoutAConnectedNodeAtThatAddress() {
		assertThat(streamTo(TARGET), equalTo(Optional.empty()));
		assertThat(streamTo(TARGET, NodeConnections.unconnected(ADDRESS)), equalTo(Optional.empty()));
		assertThat(streamTo(TARGET, NodeConnections.connected("10.0.0.2:52883", new EmbeddedChannel())),
			equalTo(Optional.empty()));
	}

	@Example
	public void hasNoStreamForAGridnodeWithoutAUsableAddress() {
		final Node node = NodeConnections.connected(ADDRESS, new EmbeddedChannel());

		assertThat(streamTo(Gridnode.builder().id("a").build(), node), equalTo(Optional.empty()));
		assertThat(streamTo(Gridnode.builder().id("a").hostName("a b:1").build(), node), equalTo(Optional.empty()));
		assertThat(streamTo(Gridnode.builder().id("a").hostName("10.0.0.1:port").build(), node),
			equalTo(Optional.empty()));
	}

	@Property
	public void hasNoStreamForAnAddressItCannotUse(@ForAll("unusableHostNames") final String hostName) {
		final Node node = NodeConnections.connected(ADDRESS, new EmbeddedChannel());

		assertThat(streamTo(at(hostName), node), equalTo(Optional.empty()));
	}

	@Example
	public void matchesTheNodeOnTheResolvedAddressNotOnTheSpelling() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final Node node = NodeConnections.connected("[::1]:52883", channel);

		assertThat(streamTo(at("[0:0:0:0:0:0:0:1]:52883"), node), equalTo(Optional.of(channel)));
		assertThat(streamTo(at("[::1]:52883"), node), equalTo(Optional.of(channel)));
		assertThat(streamTo(at("[::2]:52883"), node), equalTo(Optional.empty()));
		assertThat(streamTo(at("localhost:52883"), NodeConnections.connected("127.0.0.1:52883", channel)),
			equalTo(Optional.of(channel)));
	}

	@Example
	public void needsTheSamePortAsTheNode() {
		final Node node = NodeConnections.connected(ADDRESS, new EmbeddedChannel());

		assertThat(streamTo(at("10.0.0.1:52884"), node), equalTo(Optional.empty()));
	}

	@Example
	public void usesTheDefaultPortForAnAddressWithoutOne() {
		final EmbeddedChannel channel = new EmbeddedChannel();

		assertThat(streamTo(at("10.0.0.1"), NodeConnections.connected(ADDRESS, channel)),
			equalTo(Optional.of(channel)));
	}

	@Example
	public void hasNoStreamForAnUnresolvedNodeAtTheSameName() {
		assertThat(streamIn(at("nohost.invalid:52883"), listed(unresolved(),
			NodeConnections.connected(ADDRESS, new EmbeddedChannel()))), equalTo(Optional.empty()));
	}

	@Example
	public void skipsNodesItCannotCompareWhenLookingForTheMatch() {
		final EmbeddedChannel channel = new EmbeddedChannel();

		assertThat(streamIn(TARGET, listed(null, Node.builder().build(), unresolved(),
			NodeConnections.connected(ADDRESS, channel))), equalTo(Optional.of(channel)));
	}

	@Example
	public void hasNoStreamForANodeWithoutAConnectionValue() {
		final Node node = NodeConnections.unconnected(ADDRESS);

		node.setConnection(null);
		assertThat(streamIn(TARGET, listed(node)), equalTo(Optional.empty()));
	}

	@Example
	public void hasNoStreamForAConnectionWithoutAChannel() {
		assertThat(streamIn(TARGET, listed(NodeConnections.connected(ADDRESS, null))), equalTo(Optional.empty()));
	}

	@Example
	public void takesTheFirstNodeThatMatchesEvenWhenItIsNotConnected() {
		final EmbeddedChannel first = new EmbeddedChannel();
		final EmbeddedChannel second = new EmbeddedChannel();

		assertThat(streamIn(TARGET, listed(NodeConnections.connected(ADDRESS, first),
			NodeConnections.connected(ADDRESS, second))), equalTo(Optional.of(first)));
		assertThat(streamIn(TARGET, listed(NodeConnections.unconnected(ADDRESS),
			NodeConnections.connected(ADDRESS, second))), equalTo(Optional.empty()));
	}

	@Example
	public void takesTheMatchingNodeWhereverItIsInTheSet() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final Node other = NodeConnections.connected(OTHER_ADDRESS, new EmbeddedChannel());
		final Node match = NodeConnections.connected(ADDRESS, channel);

		assertThat(streamIn(TARGET, listed(other, match)), equalTo(Optional.of(channel)));
		assertThat(streamIn(TARGET, listed(match, other)), equalTo(Optional.of(channel)));
	}

	@Example
	public void returnsTheStreamEvenWhenItIsNotActive() {
		final EmbeddedChannel channel = new EmbeddedChannel();

		channel.close();
		assertThat(channel.isActive(), equalTo(false));
		assertThat(streamTo(TARGET, NodeConnections.connected(ADDRESS, channel)), equalTo(Optional.of(channel)));
	}

	@Example
	public void readsTheTopologyAgainOnEveryCall() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final AtomicReference<Set<Node>> topology = new AtomicReference<>(Set.of());
		final GridnodeChannels channels = new GridnodeChannels(topology::get);

		assertThat(channels.of(TARGET), equalTo(Optional.empty()));
		topology.set(Set.of(NodeConnections.connected(ADDRESS, channel)));
		assertThat(channels.of(TARGET), equalTo(Optional.of(channel)));
		topology.set(Set.of());
		assertThat(channels.of(TARGET), equalTo(Optional.empty()));
	}

	@Property
	public void findsTheMatchAmongNodesItCannotUse(@ForAll @Size(max = 6) final List<@IntRange(min = 0,
		max = 6) Integer> kinds, @ForAll @IntRange(min = 0, max = 6) final int position,
		@ForAll final boolean withMatch) {

		final EmbeddedChannel channel = new EmbeddedChannel();
		final List<Node> nodes = new ArrayList<>(kinds.stream().map(GridnodeChannelsTest::otherNode).toList());

		if (withMatch) {
			nodes.add(Math.min(position, nodes.size()), NodeConnections.connected(ADDRESS, channel));
		}

		assertThat(streamIn(TARGET, listed(nodes.toArray(Node[]::new))),
			equalTo(withMatch ? Optional.of(channel) : Optional.empty()));
	}
}
