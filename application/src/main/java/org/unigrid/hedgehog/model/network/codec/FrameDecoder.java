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
import org.unigrid.hedgehog.model.network.channel.ChannelCodec;
import org.unigrid.hedgehog.model.network.Packet;

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

	/* A ledger frame is bounded by its type, and a frame of a type nobody knows holds nothing, so a header says at once
	   whether any of the body is worth buffering. The other types keep the cap of the stream. */
	private static final BodySize ANY = new BodySize(0, Network.MAX_DATA_SIZE);
	private static final Map<Packet.Type, BodySize> BODY_SIZES = Map.of(
		Packet.Type.UNDEFINED, BodySize.exactly(0),
		Packet.Type.LEDGER_TRANSACTION,
			new BodySize(Integer.BYTES, Integer.BYTES + LedgerCodecs.MAX_TRANSACTION_SIZE),
		Packet.Type.LEDGER_CONSENSUS, new BodySize(Integer.BYTES, Integer.BYTES + WireCodec.MAX_MESSAGE_SIZE),
		Packet.Type.LEDGER_HEARTBEAT, BodySize.exactly(WireCodec.HEARTBEAT_SIZE),
		Packet.Type.LEDGER_STATUS, BodySize.exactly(WireCodec.STATUS_SIZE),
		Packet.Type.LEDGER_BLOCK_REQUEST, BodySize.exactly(Long.BYTES + WireCodec.REQUEST_SIZE),
		Packet.Type.LEDGER_BLOCK_BATCH, new BodySize(Long.BYTES + Integer.BYTES,
			Long.BYTES + Integer.BYTES + LedgerCodecs.MAX_BATCH_SIZE));

	/*
	    Packet format:
	    0..............................................................63
	    [    0xBABE    ][     type     ][         packet size          ]
	    [                  << packet specific data >>                  ]
	*/
	public FrameDecoder() {
		super(Network.MAX_DATA_SIZE, 4, 4, 0, HEADER_SIZE);
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

			in.resetReaderIndex();
			return super.decode(ctx, in);
		}

		throw new InvalidFrameMagicNumberException();
	}

	private static void requireAllowed(final ByteBuf in, final Packet.Type type, final int size) {
		final BodySize allowed = BODY_SIZES.getOrDefault(type, ANY);

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
