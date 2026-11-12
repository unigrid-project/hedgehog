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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.ThreadSet;

/* The example stops the server of the node, which the container of the class keeps for every example, so it is the only
   one of its class */
public class P2PServerRoundStopTest extends BaseDialingNodeTest {
	private static final Duration GONE = Duration.ofSeconds(20);
	private static final long HELD_SECONDS = 30;

	/* A node that stops while a round dials a peer has closed the connections it knows of by the time the dial ends, and
	   the client that the dial made is not among them. The thread that dialed closes it, with its event loop, as no
	   one else would: the node would otherwise hold a thread after it stopped. */
	@Example
	public void shouldLeaveNoClientBehindWhenItStopsWhileARoundDials() throws InterruptedException {
		final ThreadSet threads = ThreadSet.capture();
		final CountDownLatch dialing = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);

		duringDial(() -> {
			dialing.countDown();
			holdUntil(release);
		});

		addToTopology(ownNode());
		assertThat(dialing.await(HELD_SECONDS, TimeUnit.SECONDS), is(true));

		p2p.destroy();
		release.countDown();

		await().atMost(GONE).until(() -> eventLoopsStartedSince(threads), is(empty()));
	}

	private static void holdUntil(CountDownLatch latch) {
		try {
			latch.await(HELD_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
