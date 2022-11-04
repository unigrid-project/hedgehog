/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.codec.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.network.codec.api.TypedCodec;

@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
public class MintStorageEncoder implements TypedCodec<GridSpork.Type>, ChunkEncoder<MintStorage> {
	/*@Override
	public void encode(ChannelHandlerContext ctx, T spork, ByteBuf out) throws Exception {
		out.writeShort(spork.getType().getValue());
		out.writeShort(spork.getFlags());
		out.writeZero(4 32 bits);
		//out.writeLong(spork.getTimeStamp().getEpochSecond());
		//out.writeLong(spork.getPreviousTimeStamp().getEpochSecond());
		//out.writeZero(8 64 bits);

		@Cleanup("release")
		final ByteBuf data = Unpooled.buffer();
		encodeData(spork, data);

		@Cleanup("release")
		final ByteBuf previousData = Unpooled.buffer();
		encodePreviousData(spork, previousData);

		out.writeInt(data.writerIndex());
		out.writeInt(previousData.writerIndex());
		out.writeBytes(data);
		out.writeBytes(previousData);

		log.atTrace().log(() -> ByteBufUtil.hexDump(out));
	}*/
	private void encodeData(MintStorage.SporkData data, MintStorage spork, ByteBuf in) throws Exception {
		data.getMints().forEach((location, amount) -> {
			ByteBufUtils.writeNullTerminatedString(location.getAddress().getWif(), in);
			in.writeInt(location.getHeight());
			ByteBufUtils.writeNullTerminatedString(amount.toPlainString(), in);
		});
	}

	@Override
	public void encodeChunk(ChannelHandlerContext ctx, MintStorage spork, ByteBuf out) throws Exception {
		throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.MINT_STORAGE;
	}
}
