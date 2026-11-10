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
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.List;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.unigrid.hedgehog.jqwik.ThreadSet;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.PeerProtocol;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;

/* A default node whose topology thread starts a round every few milliseconds, and that dials a real peer: itself. The
   topology refuses a node of its own address, so the node is added under another one and given that of the server
   afterwards. A test says what happens in the middle of a dial, once the connection is made and before the client is
   handed to the thread. */
public abstract class BaseDialingNodeTest extends BaseDefaultNodeTest {
	private static final long QUICK_ROUND_MILLIS = 100;
	private static final String EVENT_LOOPS = "nioEventLoopGroup";

	private static volatile Runnable duringDial = () -> { };

	@Inject protected Topology topology;
	@Inject protected P2PServer p2p;

	/* The fakes outlive every try, as one that a later try applied again would be reached once more by a call that
	   proceeds from the first, without end, so they are installed once and the tests set what they do. The client
	   asks the connection which protocol it agreed on once it has opened the main stream, as its last step but the
	   chain stream, so a dial of the topology thread stands there for the test. */
	@BeforeContainer
	private static void dialQuicklyAndLetTheTestSee() {
		new MockUp<TopologyThread>() {
			@Mock
			long reconnectionTime(long nodes) {
				return QUICK_ROUND_MILLIS;
			}
		};

		new MockUp<PeerProtocol>() {
			@Mock
			boolean speaksProxy(Invocation invocation, Channel channel) {
				if (Thread.currentThread() instanceof TopologyThread) {
					duringDial.run();
				}

				return invocation.proceed();
			}
		};
	}

	@AfterTry
	void forgetWhatTheTestDid() {
		duringDial = () -> { };
	}

	/* Set before the node is added to the topology, as the next round dials it */
	protected static void duringDial(Runnable action) {
		duringDial = action;
	}

	protected Node ownNode() {
		return Node.builder().address(new InetSocketAddress(p2p.getHostName(), p2p.getPort())).build();
	}

	/* The next round dials the node */
	protected void addToTopology(Node node) {
		final InetSocketAddress address = node.getAddress();

		node.setAddress(new InetSocketAddress("192.0.2.1", address.getPort()));
		topology.addNode(node);
		topology.changeAddress(node, address);
	}

	/* The event loops started since the set was captured: every client holds one of its own */
	protected static List<String> eventLoopsStartedSince(ThreadSet threads) {
		return threads.started().stream().filter(name -> name.startsWith(EVENT_LOOPS)).toList();
	}
}
