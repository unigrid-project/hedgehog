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
	import java.util.HashMap;
	import java.util.Map;
	import java.util.Optional;
	
	import org.unigrid.hedgehog.model.Address;
	import org.unigrid.hedgehog.model.network.chunk.Chunk;
	import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
	import org.unigrid.hedgehog.model.network.chunk.ChunkType;
	import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
	import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
	import org.unigrid.hedgehog.model.spork.GridSpork;
	import org.unigrid.hedgehog.model.spork.MintStorage;
	
	@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
	public final class MintStorageDecoder
			implements TypedCodec<GridSpork.Type>,
					   ChunkDecoder<MintStorage.SporkData> {
	
		@Override
		public Optional<MintStorage.SporkData> decodeChunk(
				ChannelHandlerContext ctx,
				ByteBuf in
		) throws Exception {
	
			int entries = in.readMedium();
			in.skipBytes(5); // reserved 40 bits
	
			Map<MintStorage.SporkData.Location, BigDecimal> mints = new HashMap<>();
	
			for (int i = 0; i < entries && in.readableBytes() > 0; i++) {
	
				// --- Address ---
				Address address = new Address();
				setPrivateField(
						Address.class,
						address,
						"wif",
						ByteBufUtils.readNullTerminatedString(in)
				);
	
				int height = in.readInt();
	
				BigDecimal amount = new BigDecimal(
						ByteBufUtils.readNullTerminatedString(in)
				);
	
				// --- Location ---
				MintStorage.SporkData.Location location =
						new MintStorage.SporkData.Location();
	
				setPrivateField(
						MintStorage.SporkData.Location.class,
						location,
						"address",
						address
				);
	
				setPrivateField(
						MintStorage.SporkData.Location.class,
						location,
						"height",
						height
				);
	
				mints.put(location, amount);
			}
	
			if (mints.size() != entries) {
				return Optional.empty();
			}
	
			MintStorage.SporkData data = new MintStorage.SporkData();
	
			setPrivateField(
					MintStorage.SporkData.class,
					data,
					"mints",
					mints
			);
	
			return Optional.of(data);
		}
	
		@Override
		public GridSpork.Type getCodecType() {
			return GridSpork.Type.MINT_STORAGE;
		}
	
		// ---------- Reflection helper ----------
		private static void setPrivateField(
				Class<?> type,
				Object target,
				String fieldName,
				Object value
		) throws Exception {
	
			Field field = type.getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		}
	}
	