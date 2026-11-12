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

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.TooLongFrameException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;

/* A default node, run without --preview, has no stage for the frames of the parts that only the preview turns on, nor
   for those of the ledger, and reads none of them. A peer opens sixteen streams on a connection and eight
   connections from one address, so what the node holds of a frame it will only drop, while the frame streams in, is
   what one peer can make it hold, a hundred and twenty-eight times over. */
public class DefaultNodeFrameBufferTest {
	private static final int HEADER = 8;
	private static final int PIECE = 64 * 1024;
	private static final int FED = 32 * 1024 * 1024;
	private static final int LIMIT = 1024 * 1024;

	/* The frames of the storage that carry a fragment, as large as the storage spork makes a fragment */
	private static final Set<Packet.Type> FRAGMENTS = EnumSet.of(Packet.Type.STORE_FRAGMENT,
		Packet.Type.FRAGMENT_REPLY);

	/* The other frames of the preview, which a default node does not read */
	private static final Set<Packet.Type> OTHER_PREVIEW = EnumSet.of(Packet.Type.GRIDNODE, Packet.Type.FETCH_FRAGMENT,
		Packet.Type.HAS_FRAGMENT, Packet.Type.FRAGMENT_STATUS, Packet.Type.DELETE_GROUP, Packet.Type.STORAGE_ACK);

	/* The ledger is offered only with the preview, so a default node does not read these either */
	private static final Set<Packet.Type> LEDGER = EnumSet.of(Packet.Type.LEDGER_TRANSACTION,
		Packet.Type.LEDGER_CONSENSUS, Packet.Type.LEDGER_HEARTBEAT, Packet.Type.LEDGER_STATUS,
		Packet.Type.LEDGER_BLOCK_REQUEST, Packet.Type.LEDGER_BLOCK_BATCH);

	private static void header(final EmbeddedChannel stream, final UnpooledByteBufAllocator allocator,
		final Packet.Type type, final int declared) {

		stream.writeInbound(allocator.heapBuffer(HEADER).writeShort(FrameDecoder.MAGIC)
			.writeShort(type.getValue()).writeInt(declared));
	}

	/* The memory of the buffers that the stream asked its allocator for, which is what the stream holds of the frame:
	   its first buffer is the one that the frame decoder keeps and grows as the pieces of a frame it reads arrive */
	private static long heldAfter(final Packet.Type type, final int declared, final int fed) {
		return heldAfter(P2PServer.handlers(false, false), type, declared, fed);
	}

	private static long heldAfter(final List<ChannelHandler> handlers, final Packet.Type type, final int declared,
		final int fed) {

		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel stream = new EmbeddedChannel(handlers.toArray(ChannelHandler[]::new));

		stream.config().setAllocator(allocator);
		header(stream, allocator, type, declared);

		for (int sent = 0; sent < fed; sent += PIECE) {
			stream.writeInbound(allocator.heapBuffer(PIECE).writeZero(Math.min(PIECE, fed - sent)));
		}

		return allocator.metric().usedHeapMemory();
	}

	/* The largest body that the frame decoder takes a header of the type for, asked of the decoder itself */
	private static int largestDeclared(final Packet.Type type) {
		int admitted = 0;
		int refused = Network.MAX_DATA_SIZE;

		while (refused - admitted > 1) {
			final int middle = admitted + (refused - admitted) / 2;

			if (isRefusedAsTooLong(type, middle)) {
				refused = middle;
			} else {
				admitted = middle;
			}
		}

		return admitted;
	}

	private static boolean isRefusedAsTooLong(final Packet.Type type, final int declared) {
		final UnpooledByteBufAllocator allocator = new UnpooledByteBufAllocator(false, true);
		final EmbeddedChannel stream = new EmbeddedChannel(new FrameDecoder());

		try {
			header(stream, allocator, type, declared);
			return false;
		} catch (TooLongFrameException refused) {
			return true;
		} catch (RuntimeException tooSmall) {
			return false;
		}
	}

	/* Reads the numbers off the frames of the types, each declared at the most that the decoder admits and fed as
	   much of it as a peer would need to make the point, without ever completing it */
	private static Map<Packet.Type, String> heldBeyondTheLimit(final Set<Packet.Type> types) {
		final Map<Packet.Type, String> offenders = new TreeMap<>();

		types.forEach(type -> {
			final int declared = largestDeclared(type);
			final int fed = Math.min(declared - 1, FED);
			final long held = heldAfter(type, declared, fed);

			if (held > LIMIT) {
				offenders.put(type, "declared " + declared + " bytes, fed " + fed + ", held " + held);
			}
		});

		return offenders;
	}

	/* The meter would show nothing for any frame if it did not see the body of one that a node reads */
	@Example
	public void seesTheBodyOfAFrameThatTheNodeReads() {
		final long held = heldAfter(Packet.Type.PUBLISH_SPORK, largestDeclared(Packet.Type.PUBLISH_SPORK), 2 * LIMIT);

		assertThat(held, greaterThanOrEqualTo((long) 2 * LIMIT));
	}

	/* A node run with the preview and the ledger has the stages, and goes on collecting what they are to read */
	@Example
	public void collectsTheFramesOfThePartsOnANodeThatRunsThem() {
		for (Packet.Type type : List.of(Packet.Type.STORE_FRAGMENT, Packet.Type.LEDGER_BLOCK_BATCH)) {
			final long held = heldAfter(P2PServer.handlers(true, true), type, largestDeclared(type), 2 * LIMIT);

			assertThat(type.toString(), held, greaterThanOrEqualTo((long) 2 * LIMIT));
		}
	}

	@Example
	public void holdsLittleOfAFragmentItWillOnlyDrop() {
		assertThat("fragment frames whose body a default node holds beyond " + LIMIT + " bytes",
			heldBeyondTheLimit(FRAGMENTS), equalTo(Map.of()));
	}

	@Example
	public void holdsLittleOfTheOtherFramesOfThePreviewItWillOnlyDrop() {
		assertThat("frames of the preview whose body a default node holds beyond " + LIMIT + " bytes",
			heldBeyondTheLimit(OTHER_PREVIEW), equalTo(Map.of()));
	}

	@Example
	public void holdsLittleOfTheFramesOfTheLedgerItWillOnlyDrop() {
		assertThat("frames of the ledger whose body a default node holds beyond " + LIMIT + " bytes",
			heldBeyondTheLimit(LEDGER), equalTo(Map.of()));
	}
}
