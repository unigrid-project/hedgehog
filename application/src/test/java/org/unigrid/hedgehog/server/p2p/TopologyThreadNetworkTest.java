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

package org.unigrid.hedgehog.server.p2p;

import io.netty.channel.Channel;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.jqwik.ThreadSet;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.QuicDialedPeer;

public class TopologyThreadNetworkTest extends BaseDialingNodeTest {
	private static final Duration CLOSED = Duration.ofSeconds(20);
	private static final Duration AT_ONCE = Duration.ofSeconds(1);

	/* The main stream of the connection that a round dialed to the node, while it is open */
	private Optional<Channel> dialedTo(Node node) {
		return topology.cloneNodes().stream().filter(node::equals).findFirst().flatMap(Node::getConnection)
			.map(Connection::getChannel).filter(Channel::isActive);
	}

	/* The peer ends the main stream alone, as a node does with a stream on which a frame does not come, and says
	   nothing else: the node closes the connection with the stream, keeps the peer in its topology, and the next
	   round dials it again */
	private void endsTheMainStreamAndIsDialedAgain(QuicDialedPeer peer, Node node) {
		await().atMost(CLOSED).until(() -> dialedTo(node).isPresent() && peer.streams().size() == 1);

		final Channel stream = dialedTo(node).get();

		peer.streams().get(0).close();

		await("the node to close the stream that its peer ended, and the connection").atMost(AT_ONCE)
			.until(() -> !stream.isActive() && !stream.parent().isActive());
		assertThat(topology.cloneNodes(), hasItem(node));

		await("the next round to dial the peer again").atMost(CLOSED)
			.until(() -> dialedTo(node).filter(dialed -> dialed != stream).isPresent() && peer.streams().size() == 2);
		assertThat(peer.connections().size(), is(2));
		assertThat(topology.cloneNodes(), hasItem(node));
	}

	/* A node that leaves the topology is seen by no round again, so the connection that one dialed to it is closed
	   here */
	private void letGoOf(Node node) {
		topology.removeConfigured(node).flatMap(Node::getConnection).ifPresent(Connection::closeDirty);
	}

	/* An operator removes a node over REST while the thread dials it (NodeResource), and the topology then has no node
	   to hand the client that the dial made to. The client is nobody's from there on, so it is closed with its event
	   loop rather than left to hold a connection and a thread for good. */
	@Example
	public void shouldCloseTheClientOfANodeThatWasRemovedWhileItWasDialed() {
		final ThreadSet threads = ThreadSet.capture();
		final AtomicBoolean dialed = new AtomicBoolean();
		final Node node = ownNode();

		duringDial(() -> {
			topology.removeNode(node);
			dialed.set(true);
		});

		addToTopology(node);
		await().untilTrue(dialed);
		await().atMost(CLOSED).until(() -> eventLoopsStartedSince(threads), is(empty()));
	}

	/* A peer that ends the main stream of a connection is done with it, and reads nothing more of what the node writes
	   there. The stream of the node stays open at its own end, so the node would hold the peer as connected until its
	   pings went unanswered, a minute or more later, and only the round after that would dial it again. */
	@Example
	public void shouldDialAPeerAgainThatEndedTheMainStreamOfTheConnectionToIt() throws Exception {
		try (QuicDialedPeer peer = QuicDialedPeer.offering(Network.getProtocols())) {
			final Node node = peer.node();

			try {
				topology.addNode(node);
				endsTheMainStreamAndIsDialedAgain(peer, node);
				assertThat(topology.cloneConfigured(), not(hasItem(node)));
			} finally {
				letGoOf(node);
			}
		}
	}

	/* A node that an operator configured stays configured through it */
	@Example
	public void shouldDialAConfiguredPeerAgainThatEndedTheMainStreamOfTheConnectionToIt() throws Exception {
		try (QuicDialedPeer peer = QuicDialedPeer.offering(Network.getProtocols())) {
			final Node node = peer.node();

			try {
				topology.addConfigured(node);
				endsTheMainStreamAndIsDialedAgain(peer, node);
				assertThat(topology.cloneConfigured(), hasItem(node));
			} finally {
				letGoOf(node);
			}
		}
	}
}
