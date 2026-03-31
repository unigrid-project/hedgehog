/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
	
	import java.lang.reflect.Field;
	import java.math.BigDecimal;
	
	import org.unigrid.hedgehog.model.network.chunk.Chunk;
	import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
	import org.unigrid.hedgehog.model.network.chunk.ChunkType;
	import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
	import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
	import org.unigrid.hedgehog.model.spork.GridSpork;
	import org.unigrid.hedgehog.model.spork.MintSupply;
	
	@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
	public final class MintSupplyEncoder
			implements TypedCodec<GridSpork.Type>,
					   ChunkEncoder<MintSupply.SporkData> {
	
		@Override
		public void encodeChunk(
				ChannelHandlerContext ctx,
				MintSupply.SporkData data,
				ByteBuf out
		) {
			BigDecimal maxSupply = getPrivateField(data, "maxSupply");
	
			ByteBufUtils.writeNullTerminatedString(
					maxSupply.toPlainString(),
					out
			);
		}
	
		@Override
		public GridSpork.Type getCodecType() {
			return GridSpork.Type.MINT_SUPPLY;
		}
	
		@SuppressWarnings("unchecked")
		private static <T> T getPrivateField(Object target, String fieldName) {
			try {
				Field field = target.getClass().getDeclaredField(fieldName);
				field.setAccessible(true);
				return (T) field.get(target);
			} catch (Exception e) {
				throw new IllegalStateException(
						"Unable to access field '" + fieldName + "' in " + target.getClass(),
						e
				);
			}
		}
	}
	