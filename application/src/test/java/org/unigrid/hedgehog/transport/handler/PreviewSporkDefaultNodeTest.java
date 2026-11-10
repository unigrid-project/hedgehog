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

package org.unigrid.hedgehog.transport.handler;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.PreviewFrames;
import org.unigrid.hedgehog.model.network.QuicPeer;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.server.BaseDefaultNodeSporkTest;

/* A peer sends a node run without --preview the sporks of the parts that this release leaves off, signed by the board
   in the way that a node which took them would store: both co-signed, and as a proposal that waits for the second key.
   The node reads each frame whole and goes on, but takes none of them: it stores none, holds none as a proposal, sends
   none on to the peer that reads every type, and does not note one as a sign that it is behind. A spork of this release
   on the same stream after them is taken all the same, which shows that the signing here is one the node accepts. */
public class PreviewSporkDefaultNodeTest extends BaseDefaultNodeSporkTest {
	@BeforeTry
	public void startWithoutTheSporks() {
		forgetTheSporks();
	}

	private static ByteBuf frameOf(final GridSpork spork) {
		return PreviewFrames.frameOf(new PublishSporkEncoder(), PublishSpork.builder().gridSpork(spork).build());
	}

	/* The answer to the ping says that the node has read every frame before it */
	private static void send(final QuicPeer.Stream stream, final List<GridSpork> sporks) {
		sporks.forEach(spork -> stream.send(frameOf(spork)));
		stream.awaitAnswer(stream.ping());
	}

	private static List<GridSpork> sporksOfThePreview() {
		final List<GridSpork> sporks = new ArrayList<>();

		OF_THE_PREVIEW.forEach(type -> {
			sporks.add(cosigned(type));
			sporks.add(proposed(type));
		});

		return sporks;
	}

	@Example
	public void shouldTakeNoSporkOfThePreviewFromAPeerButOneOfThisRelease() throws InterruptedException {
		final List<GridSpork> sporks = sporksOfThePreview();
		final GridSpork control = mintSupply();

		sporks.stream().filter(spork -> !spork.isPending()).forEach(this::assertWouldMakeANodeBehind);

		try (QuicPeer peer = QuicPeer.dial(NetOptions.getHost(), NetOptions.getPort(), Network.getProtocols())) {
			final QuicPeer.Stream stream = peer.open();

			send(stream, sporks);
			assertNoSporkOfThePreviewTaken();

			send(stream, List.of(control));
			assertTakenAndSentOn(control);
			assertNoSporkOfThePreviewTaken();
		}
	}
}
