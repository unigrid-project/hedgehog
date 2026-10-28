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
import java.util.Map;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.gridnode.SealingKeyFile;
import org.unigrid.hedgehog.model.network.channel.ChannelCodec;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.GroupKey;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

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
	private static final int HEAD_SIZE = WRITER_SIZE + 2 * Long.BYTES;
	private static final int HEADS_SIZE = Short.BYTES + ChainCodecs.MAX_HEADS_PER_PACKET * HEAD_SIZE;
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
		Map.entry(Packet.Type.CHAIN_HEADS, new BodySize(CHAIN_ID_SIZE + Short.BYTES, CHAIN_ID_SIZE + HEADS_SIZE)),
		Map.entry(Packet.Type.FETCH_ENTRIES, new BodySize(CHAIN_ID_SIZE + Short.BYTES,
			CHAIN_ID_SIZE + Short.BYTES + ChainCodecs.MAX_HEADS_PER_PACKET * RANGE_SIZE)),
		Map.entry(Packet.Type.ENTRY_REFUSED, BodySize.exactly(CHAIN_ID_SIZE + WRITER_SIZE + Long.BYTES + 1)),
		Map.entry(Packet.Type.LEAVE_CHAIN, BodySize.exactly(CHAIN_ID_SIZE + WRITER_SIZE)));

	private final Map<Packet.Type, BodySize> bodySizes;

	/*
	    Packet format:
	    0..............................................................63
	    [    0xBABE    ][     type     ][         packet size          ]
	    [                  << packet specific data >>                  ]
	*/
	public FrameDecoder() {
		this(Network.MAX_DATA_SIZE, BODY_SIZES);
	}

	private FrameDecoder(final int maxFrameBytes, final Map<Packet.Type, BodySize> bodySizes) {
		super(maxFrameBytes, 4, 4, 0, HEADER_SIZE);
		this.bodySizes = bodySizes;
	}

	/* Frames of a limit of their own, far below the one of the main stream, header included */
	public static FrameDecoder forChainStream() {
		return new FrameDecoder(ChainCodecs.MAX_FRAME_BYTES, CHAIN_BODY_SIZES);
	}

	@Override
	protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
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

			/* Tell the pipeline what packet to handle next */
			ctx.channel().attr(Packet.KEY).set(type);
			ctx.channel().attr(PACKET_SIZE_KEY).set(size);

			/* The header is read here, ahead of the superclass, so its discard mode can never run: the bytes
			   behind a frame over the limit are dropped only because the exception closes the stream */
			in.resetReaderIndex();
			return super.decode(ctx, in);
		}

		throw new InvalidFrameMagicNumberException();
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
