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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.gridnode.SealingKeyFile;
import org.unigrid.hedgehog.model.network.channel.ChannelCodec;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

@Slf4j
@ChannelCodec(priority = -10)
public class FrameDecoder extends LengthFieldBasedFrameDecoder {
	public static final int MAGIC = 0xBABE;
	public static final AttributeKey<Integer> PACKET_SIZE_KEY = AttributeKey.valueOf("PACKET_SIZE");
	private static final int HEADER_SIZE = 8;

	private record BodySize(int least, int most) {
		private static BodySize exactly(final int size) {
			return new BodySize(size, size);
		}
	}

	/* The largest spork an honest node sends is a mint storage at its cap, its data and its previous data both, with
	   two signatures and a signature log at its cap. That is under ten megabytes, and the bound leaves room above. */
	static final int MAX_SPORK_SIZE = 16 * 1024 * 1024;

	private static final int PEER_SIZE = Long.BYTES + PublishPeersDecoder.MAX_HOST_ADDRESS + 1;
	private static final int GRIDNODE_SIZE = 1 + Long.BYTES + 3 * Short.BYTES + GridnodeDecoder.ID_LENGTH
		+ SealingKeyFile.KEY_SIZE;
	private static final int GROUPS_SIZE = Long.BYTES + Short.BYTES;
	private static final int TOMBSTONE_SIZE = GroupKey.PUBLIC_KEY_SIZE + Long.BYTES + GroupKey.SIGNATURE_SIZE;
	private static final int MAX_GROUPS = StorageCodecs.MAX_GROUPS_PER_PACKET;

	/* Every frame is bounded by its type, so a header says at once whether any of the body is worth buffering, and a
	   frame of a type that no codec reads holds nothing. A fragment is as large as the storage spork makes it, so
	   the two types that carry one keep the cap of the stream. */
	private static final BodySize NOTHING = BodySize.exactly(0);
	private static final Map<Packet.Type, BodySize> BODY_SIZES = Map.ofEntries(
		Map.entry(Packet.Type.HELLO, BodySize.exactly(2 * Long.BYTES)),
		Map.entry(Packet.Type.PING, BodySize.exactly(2 * Long.BYTES)),
		Map.entry(Packet.Type.ASK_PEERS, BodySize.exactly(Long.BYTES)),
		Map.entry(Packet.Type.PUBLISH_PEERS, new BodySize(Long.BYTES, Long.BYTES + Short.MAX_VALUE * PEER_SIZE)),
		Map.entry(Packet.Type.ASK_NODE_DETAILS, BodySize.exactly(Long.BYTES)),
		Map.entry(Packet.Type.PUBLISH_SPORK, new BodySize(Short.BYTES, MAX_SPORK_SIZE)),
		Map.entry(Packet.Type.GRIDNODE, new BodySize(GRIDNODE_SIZE,
			GRIDNODE_SIZE + GridnodeDecoder.MAX_HOST_LENGTH + GridnodeDecoder.MAX_SIGNATURE_LENGTH)),
		Map.entry(Packet.Type.STORE_FRAGMENT, new BodySize(Long.BYTES + Integer.BYTES, Network.MAX_DATA_SIZE)),
		Map.entry(Packet.Type.FETCH_FRAGMENT, BodySize.exactly(Long.BYTES + GroupId.SIZE)),
		Map.entry(Packet.Type.FRAGMENT_REPLY, new BodySize(Long.BYTES + 1 + Integer.BYTES, Network.MAX_DATA_SIZE)),
		Map.entry(Packet.Type.HAS_FRAGMENT, new BodySize(GROUPS_SIZE, GROUPS_SIZE + MAX_GROUPS * GroupId.SIZE)),
		Map.entry(Packet.Type.FRAGMENT_STATUS, new BodySize(GROUPS_SIZE,
			GROUPS_SIZE + MAX_GROUPS * (GroupId.SIZE + 2 + TOMBSTONE_SIZE))),
		Map.entry(Packet.Type.DELETE_GROUP, BodySize.exactly(Long.BYTES + GroupId.SIZE + Integer.BYTES
			+ TOMBSTONE_SIZE + Integer.BYTES)),
		Map.entry(Packet.Type.STORAGE_ACK, BodySize.exactly(Long.BYTES + 1)),
		Map.entry(Packet.Type.LEDGER_TRANSACTION,
			new BodySize(Integer.BYTES, Integer.BYTES + LedgerCodecs.MAX_TRANSACTION_SIZE)),
		Map.entry(Packet.Type.LEDGER_CONSENSUS,
			new BodySize(Integer.BYTES, Integer.BYTES + WireCodec.MAX_MESSAGE_SIZE)),
		Map.entry(Packet.Type.LEDGER_HEARTBEAT, BodySize.exactly(WireCodec.HEARTBEAT_SIZE)),
		Map.entry(Packet.Type.LEDGER_STATUS, BodySize.exactly(WireCodec.STATUS_SIZE)),
		Map.entry(Packet.Type.LEDGER_BLOCK_REQUEST, BodySize.exactly(Long.BYTES + WireCodec.REQUEST_SIZE)),
		Map.entry(Packet.Type.LEDGER_BLOCK_BATCH, new BodySize(Long.BYTES + Integer.BYTES,
			Long.BYTES + Integer.BYTES + LedgerCodecs.MAX_BATCH_SIZE)));

	private static final int CHAIN_ID_SIZE = ChainId.SIZE;
	private static final int WRITER_SIZE = WriterId.SIZE;
	private static final int HEADS_SIZE = Short.BYTES + ChainCodecs.MAX_HEADS_PER_PACKET * ChainCodecs.HEAD_BYTES;
	private static final int RANGE_SIZE = WRITER_SIZE + 2 * Long.BYTES;

	/* The stream that chain traffic has to itself is open to any peer and takes the chain packets, and the ping that
	   opens it, and nothing else, as the main stream takes nothing of the chain */
	private static final Map<Packet.Type, BodySize> CHAIN_BODY_SIZES = Map.ofEntries(
		Map.entry(Packet.Type.PING, BodySize.exactly(2 * Long.BYTES)),
		Map.entry(Packet.Type.JOIN_CHAIN, new BodySize(Long.BYTES + WRITER_SIZE + 1,
			Long.BYTES + WRITER_SIZE + 1 + CHAIN_ID_SIZE)),
		Map.entry(Packet.Type.JOIN_REPLY, new BodySize(Long.BYTES + 1,
			Long.BYTES + 1 + CHAIN_ID_SIZE + HEADS_SIZE)),
		Map.entry(Packet.Type.CHAIN_ENTRY, new BodySize(Short.BYTES,
			ChainCodecs.MAX_FRAME_BYTES - ChainCodecs.FRAME_HEADER_BYTES)),
		Map.entry(Packet.Type.CHAIN_STEM, new BodySize(Short.BYTES,
			ChainCodecs.MAX_FRAME_BYTES - ChainCodecs.FRAME_HEADER_BYTES)),
		Map.entry(Packet.Type.CHAIN_HEADS, new BodySize(CHAIN_ID_SIZE + Short.BYTES, CHAIN_ID_SIZE + HEADS_SIZE)),
		Map.entry(Packet.Type.FETCH_ENTRIES, new BodySize(CHAIN_ID_SIZE + Short.BYTES,
			CHAIN_ID_SIZE + Short.BYTES + ChainCodecs.MAX_HEADS_PER_PACKET * RANGE_SIZE)),
		Map.entry(Packet.Type.ENTRY_REFUSED, BodySize.exactly(CHAIN_ID_SIZE + WRITER_SIZE + Long.BYTES + 1)),
		Map.entry(Packet.Type.LEAVE_CHAIN, BodySize.exactly(CHAIN_ID_SIZE + WRITER_SIZE)));

	/* The frames of the gridnodes and the storage, which only a node run with the preview has stages for, and those of
	   the ledger, which only a node that offers the ledger has stages for */
	private static final Set<Packet.Type> PREVIEW_TYPES = Collections.unmodifiableSet(EnumSet.of(Packet.Type.GRIDNODE,
		Packet.Type.STORE_FRAGMENT, Packet.Type.FETCH_FRAGMENT, Packet.Type.FRAGMENT_REPLY, Packet.Type.HAS_FRAGMENT,
		Packet.Type.FRAGMENT_STATUS, Packet.Type.DELETE_GROUP, Packet.Type.STORAGE_ACK));
	private static final Set<Packet.Type> LEDGER_TYPES = Collections.unmodifiableSet(EnumSet.of(
		Packet.Type.LEDGER_TRANSACTION, Packet.Type.LEDGER_CONSENSUS, Packet.Type.LEDGER_HEARTBEAT,
		Packet.Type.LEDGER_STATUS, Packet.Type.LEDGER_BLOCK_REQUEST, Packet.Type.LEDGER_BLOCK_BATCH));

	/* The stream holds what has arrived of a frame until the frame is whole, and every byte of it counts as a sign of
	   its peer, so the pings never close the stream of a peer that lets a frame crawl in. A frame is given a minute
	   to be whole in, and a second more for every 32 KiB that its header says it holds. */
	private static final int SECONDS_FOR_A_FRAME = 60;
	private static final int BYTES_A_SECOND = 32 * 1024;

	private final int maxBodyBytes;
	private final Map<Packet.Type, BodySize> bodySizes;
	private final Set<Packet.Type> unreadTypes;
	private int unreadBytes;
	private Future<?> deadline;

	/*
	    Packet format:
	    0..............................................................63
	    [    0xBABE    ][     type     ][         packet size          ]
	    [                  << packet specific data >>                  ]
	*/
	public FrameDecoder() {
		this(Network.MAX_DATA_SIZE, BODY_SIZES, Set.of());
	}

	private FrameDecoder(final int maxFrameBytes, final Map<Packet.Type, BodySize> bodySizes,
		final Set<Packet.Type> unreadTypes) {

		super(maxFrameBytes, 4, 4, 0, HEADER_SIZE);
		this.maxBodyBytes = maxFrameBytes - HEADER_SIZE;
		this.bodySizes = bodySizes;
		this.unreadTypes = unreadTypes;
	}

	/* Frames of a limit of their own, far below the one of the main stream, header included */
	public static FrameDecoder forChainStream() {
		return new FrameDecoder(ChainCodecs.MAX_FRAME_BYTES, CHAIN_BODY_SIZES, Set.of());
	}

	/* A peer sends the frames of every part it runs, and a node that does without a part has no stage that reads
	   them: the frame would be collected whole, a fragment of hundreds of megabytes among them, only to be dropped
	   behind the last stage. The header of such a frame is held to the bounds of its type as on any node, and its
	   body is let go of as it arrives. */
	public static FrameDecoder forMainStream(final boolean ledger, final boolean preview) {
		final Set<Packet.Type> unreadTypes = EnumSet.noneOf(Packet.Type.class);

		if (!ledger) {
			unreadTypes.addAll(LEDGER_TYPES);
		}

		if (!preview) {
			unreadTypes.addAll(PREVIEW_TYPES);
		}

		return new FrameDecoder(Network.MAX_DATA_SIZE, BODY_SIZES, unreadTypes);
	}

	@Override
	protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		if (unreadBytes > 0) {
			return discard(in);
		}

		/* A read can end inside the header, and reading on would fail the decoder and close the stream */
		if (in.readableBytes() < HEADER_SIZE) {
			return null;
		}

		in.markReaderIndex();
		final int magic = in.readUnsignedShort();

		if (MAGIC == magic) {
			final Packet.Type type = Packet.Type.get(in.readShort());
			final int size = in.readInt();

			requireAllowed(in, type, size);

			if (isUnread(type, size)) {
				unreadBytes = size;
				return discard(in);
			}

			/* Tell the pipeline what packet to handle next */
			ctx.channel().attr(Packet.KEY).set(type);
			ctx.channel().attr(PACKET_SIZE_KEY).set(size);

			/* The header is read here, ahead of the superclass, so its discard mode can never run: the bytes
			   behind a frame over the limit are dropped only because the exception closes the stream */
			in.resetReaderIndex();
			return timed(ctx, type, size, super.decode(ctx, in));
		}

		throw new InvalidFrameMagicNumberException();
	}

	/* The time of a frame runs from the read that brought its header and left the frame unfinished, and what
	   arrives of the frame after that gives it no more. A frame that is whole ends it. A frame that is passed over
	   has none, as the stream holds nothing of it. */
	private Object timed(final ChannelHandlerContext ctx, final Packet.Type type, final int size, final Object frame) {
		if (frame != null) {
			cancelDeadline();
		} else if (deadline == null) {
			final long seconds = SECONDS_FOR_A_FRAME + size / BYTES_A_SECOND;

			deadline = ctx.executor().schedule(() -> closeUnfinished(ctx, type, size, seconds), seconds,
				TimeUnit.SECONDS);
		}

		return frame;
	}

	/* The stream frees what it holds of the frame as it closes. A frame that does not come is the doing of the peer,
	   which can leave any number of them unfinished, so it is told in one line at debug level. */
	private void closeUnfinished(final ChannelHandlerContext ctx, final Packet.Type type, final int size,
		final long seconds) {

		log.atDebug().log("Closed a stream: a {} frame of {} bytes was not whole after {} seconds", type, size,
			seconds);
		ctx.close();
	}

	private void cancelDeadline() {
		if (deadline != null) {
			deadline.cancel(false);
			deadline = null;
		}
	}

	/* What is left of a frame is decoded once more as the stream closes, which is not to start its time again, so the
	   time is ended behind that */
	@Override
	public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
		try {
			super.channelInactive(ctx);
		} finally {
			cancelDeadline();
		}
	}

	@Override
	protected void handlerRemoved0(final ChannelHandlerContext ctx) throws Exception {
		cancelDeadline();
		super.handlerRemoved0(ctx);
	}

	/* A frame longer than the stream takes is left to be refused as on a node that reads its type */
	private boolean isUnread(final Packet.Type type, final int size) {
		return unreadTypes.contains(type) && size <= maxBodyBytes;
	}

	/* What has arrived of a body that no stage reads is passed over, so the buffer of the stream is used up and freed
	   with every read, and only the count of the bytes still to come is kept. No frame comes of it. */
	private Object discard(final ByteBuf in) {
		final int arrived = Math.min(unreadBytes, in.readableBytes());

		in.skipBytes(arrived);
		unreadBytes -= arrived;
		return null;
	}

	private void requireAllowed(final ByteBuf in, final Packet.Type type, final int size) {
		final BodySize allowed = bodySizes.getOrDefault(type, NOTHING);

		if (size > allowed.most()) {
			throw refused(in, new TooLongFrameException("A " + type + " frame holds at most " + allowed.most()
				+ " bytes, not " + size));
		}

		if (size < allowed.least()) {
			throw refused(in, new CorruptedFrameException("A " + type + " frame holds at least "
				+ allowed.least() + " bytes, not " + size));
		}
	}

	/* What the stream holds after a refused header cannot be framed again, so none of it is kept */
	private static DecoderException refused(final ByteBuf in, final DecoderException reason) {
		in.skipBytes(in.readableBytes());
		return reason;
	}
}
