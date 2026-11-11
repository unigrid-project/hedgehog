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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.LedgerAndChainFrames;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.PreviewFrames;
import org.unigrid.hedgehog.model.network.QuicPeer;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.option.NetOptions;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;

/* A node run without --preview has no stage for the parts that this release leaves off, but a peer run with it sends
   their frames all the same, and any peer may send them. Over real QUIC streams to a node that is up, none of them
   may start a part, build what a part builds, or start a thread of one, and the node goes on answering. */
public class P2PServerPreviewFramesTest extends BaseDefaultNodeTest {
	private static final int FRAMES_PER_TYPE = 1000;
	private static final int FRAMES_WITH_A_STREAM_EACH = 100;

	/* From the announcement of a gridnode to the last type: the storage, the ledger and the chain follow it */
	private static final Set<Packet.Type> TYPES_OF_THE_PREVIEW = EnumSet.range(Packet.Type.GRIDNODE,
		Packet.Type.CHAIN_STEM);

	/* The streams of a test, each the main stream of a connection of its own, as the node serves a connection one main
	   stream and closes a further one unread. Sixteen connections are open together, and are closed once the next
	   one after them is dialed. */
	private static final class Streams implements AutoCloseable {
		private static final int TOGETHER = 16;

		private final List<QuicPeer> peers = new ArrayList<>();

		QuicPeer.Stream open() throws InterruptedException {
			if (!hasRoom()) {
				close();
			}

			final QuicPeer peer = QuicPeer.dial(NetOptions.getHost(), NetOptions.getPort(), Network.getProtocols());

			peers.add(peer);
			return peer.open();
		}

		/* Whether another connection may be open beside the ones that are */
		boolean hasRoom() {
			return peers.size() < TOGETHER;
		}

		@Override
		public void close() throws InterruptedException {
			for (QuicPeer peer : peers) {
				peer.close();
			}

			peers.clear();
		}
	}

	private record Pinged(QuicPeer.Stream stream, long nanoTime) {
		private void awaitAnswerOrClose() {
			stream.awaitAnswerOrClose(nanoTime);
			stream.close();
		}
	}

	private static Packet.Type typeOf(final ByteBuf frame) {
		return Packet.Type.get(frame.getShort(Short.BYTES));
	}

	/* A frame of the type with an empty body: nothing but the header */
	private static ByteBuf headerOf(final Packet.Type type) {
		return Unpooled.buffer().writeShort(FrameDecoder.MAGIC).writeShort(type.getValue()).writeInt(0);
	}

	private static List<ByteBuf> everyFrame() {
		final List<ByteBuf> frames = new ArrayList<>(PreviewFrames.ofTheParts());

		frames.addAll(LedgerAndChainFrames.ofTheLedger());
		frames.addAll(LedgerAndChainFrames.ofTheChain());
		return frames;
	}

	/* Each stream carries a ping behind its frames: the answer says that the node has read everything before it, and
	   a node that closes the stream instead says it by the closing. Several streams are in flight together, as an
	   answer takes a moment. */
	private static void sendTimes(final Streams streams, final ByteBuf frame, final int times, final int perStream)
		throws InterruptedException {

		int sent = 0;

		while (sent < times) {
			final List<Pinged> inFlight = new ArrayList<>();

			do {
				final QuicPeer.Stream stream = streams.open();
				final int count = Math.min(perStream, times - sent);

				for (int i = 0; i < count; i++) {
					stream.send(frame);
				}

				sent += count;
				inFlight.add(new Pinged(stream, stream.ping()));
			} while (sent < times && streams.hasRoom());

			inFlight.forEach(Pinged::awaitAnswerOrClose);
		}
	}

	private static void assertAnswersAPingOnAFreshStream(final Streams streams) throws InterruptedException {
		final QuicPeer.Stream fresh = streams.open();

		fresh.awaitAnswer(fresh.ping());
		fresh.close();
	}

	private void assertStillADefaultNode(final Packet.Type after) {
		assertThat("Created after " + after + " frames", createdOfThePreview(), is(empty()));
		assertThat("Threads after " + after + " frames", threadsOfThePreview(), is(empty()));
	}

	private interface Sending {
		void of(Streams streams, ByteBuf frame) throws InterruptedException;
	}

	private void assertTakesFrames(final List<ByteBuf> frames, final Sending sending) throws InterruptedException {
		try (Streams streams = new Streams()) {
			for (ByteBuf frame : frames) {
				sending.of(streams, frame);
				assertStillADefaultNode(typeOf(frame));
			}

			assertAnswersAPingOnAFreshStream(streams);
			assertStillADefaultNode(typeOf(frames.getLast()));
		}
	}

	@Example
	public void shouldTestEveryTypeOfThePreview() {
		final Set<Packet.Type> types = EnumSet.noneOf(Packet.Type.class);

		everyFrame().forEach(frame -> types.add(typeOf(frame)));
		assertThat(types, equalTo(TYPES_OF_THE_PREVIEW));
		assertThat(everyFrame().size(), is(TYPES_OF_THE_PREVIEW.size()));
	}

	@Example
	public void shouldTakeTheFramesOfTheGridnodeAndTheStorageAndStayADefaultNode() throws InterruptedException {
		assertTakesFrames(PreviewFrames.ofTheParts(),
			(streams, frame) -> sendTimes(streams, frame, FRAMES_PER_TYPE, FRAMES_PER_TYPE));
	}

	@Example
	public void shouldTakeTheFramesOfTheLedgerAndStayADefaultNode() throws InterruptedException {
		assertTakesFrames(LedgerAndChainFrames.ofTheLedger(),
			(streams, frame) -> sendTimes(streams, frame, FRAMES_PER_TYPE, FRAMES_PER_TYPE));
	}

	/* The main stream refuses a chain frame that holds anything, and the node closes the stream that carries it, so
	   each of those needs a stream of its own, on a connection of its own, and the node takes some tens of
	   milliseconds to set one up. The rest of the frames of a type are bare headers, which the stream lets through,
	   and go on one stream. */
	@Example
	public void shouldTakeTheFramesOfTheChainAndStayADefaultNode() throws InterruptedException {
		assertTakesFrames(LedgerAndChainFrames.ofTheChain(), (streams, frame) -> {
			sendTimes(streams, frame, FRAMES_WITH_A_STREAM_EACH, 1);
			sendTimes(streams, headerOf(typeOf(frame)), FRAMES_PER_TYPE - FRAMES_WITH_A_STREAM_EACH,
				FRAMES_PER_TYPE - FRAMES_WITH_A_STREAM_EACH);
		});
	}
}
