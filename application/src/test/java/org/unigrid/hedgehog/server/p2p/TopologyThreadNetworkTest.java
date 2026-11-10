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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.ThreadSet;
import org.unigrid.hedgehog.model.network.Node;

public class TopologyThreadNetworkTest extends BaseDialingNodeTest {
	private static final Duration CLOSED = Duration.ofSeconds(20);

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
}
