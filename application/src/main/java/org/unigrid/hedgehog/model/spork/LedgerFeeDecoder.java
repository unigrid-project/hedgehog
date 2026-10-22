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

package org.unigrid.hedgehog.model.spork;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import java.math.BigDecimal;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class LedgerFeeDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<LedgerFee.SporkData> {
	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [        minimum fee in units of 1e-8 coins (u64, big-endian)  ]
	    [ minimum amount to a new account in units of 1e-8 coins (u64) ]
	*/
	@Override
	public Optional<LedgerFee.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final LedgerFee.SporkData data = new LedgerFee.SporkData();

		data.setMinimumTransferFee(amount(in.readLong(), LedgerFee.SporkData.MAX_UNITS, "minimum fee"));
		data.setMinimumNewAccountAmount(amount(in.readLong(), LedgerFee.SporkData.MAX_NEW_ACCOUNT_UNITS,
			"minimum amount to a new account"));
		return Optional.of(data);
	}

	/* Unsigned, as a signed reading would take every value with the high bit set for a small amount */
	private static BigDecimal amount(long units, long cap, String name) {
		if (Long.compareUnsigned(units, cap) > 0) {
			throw new CorruptedFrameException("A " + name + " of " + Long.toUnsignedString(units)
				+ " units exceeds the cap of " + cap);
		}

		return BigDecimal.valueOf(units, LedgerFee.SporkData.DECIMALS);
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.LEDGER_FEE;
	}
}
