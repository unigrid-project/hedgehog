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
	import java.time.Duration;
	import java.time.Instant;
	import java.util.Map;
	
	import org.unigrid.hedgehog.model.Address;
	import org.unigrid.hedgehog.model.network.chunk.Chunk;
	import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
	import org.unigrid.hedgehog.model.network.chunk.ChunkType;
	import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
	import org.unigrid.hedgehog.model.network.util.ByteBufUtils;
	import org.unigrid.hedgehog.model.spork.GridSpork;
	import org.unigrid.hedgehog.model.spork.VestingStorage;
	
	@Chunk(type = ChunkType.ENCODER, group = ChunkGroup.GRIDSPORK)
	public final class VestingStorageEncoder
			implements TypedCodec<GridSpork.Type>,
					   ChunkEncoder<VestingStorage.SporkData> {
	
		/*
			Chunk format:
			0..............................................................63
			[         << Spork Header (AbstractGridSporkDecoder) >>        ]
			[     n= num mints     ][               reserved               ]
		   n[                     << address (0-term) >>                   ]
			[                     vesting start (seconds)                  ]
			[                    vesting duration (seconds)                ]
			[                          vesting parts                   ...n]
		*/
	
		@Override
		@SuppressWarnings("unchecked")
		public void encodeChunk(
				ChannelHandlerContext ctx,
				VestingStorage.SporkData data,
				ByteBuf out
		) throws Exception {
	
			if (data == null) {
				throw new IllegalArgumentException("VestingStorage.SporkData is null");
			}
	
			Map<Address, VestingStorage.SporkData.Vesting> vestings =
					(Map<Address, VestingStorage.SporkData.Vesting>)
							getPrivate(data, "vestingAddresses");
	
			if (vestings == null) {
				throw new IllegalArgumentException("vestingAddresses is null");
			}
	
			out.writeMedium(vestings.size());
			out.writeZero(5); // reserved 40 bits
	
			for (Map.Entry<Address, VestingStorage.SporkData.Vesting> e : vestings.entrySet()) {
	
				Address address = e.getKey();
				VestingStorage.SporkData.Vesting vesting = e.getValue();
	
				String wif = (String) getPrivate(address, "wif");
				Instant start = (Instant) getPrivate(vesting, "start");
				Duration duration = (Duration) getPrivate(vesting, "duration");
				int parts = (int) getPrivate(vesting, "parts");
	
				ByteBufUtils.writeNullTerminatedString(wif, out);
				out.writeLong(start.getEpochSecond());
				out.writeLong(duration.getSeconds());
				out.writeInt(parts);
			}
		}
	
		@Override
		public GridSpork.Type getCodecType() {
			return GridSpork.Type.VESTING_STORAGE;
		}
	
		// =========================
		// Reflection helper
		// =========================
	
		private static Object getPrivate(Object target, String field)
				throws Exception {
	
			Field f = target.getClass().getDeclaredField(field);
			f.setAccessible(true);
			return f.get(target);
		}
	}
	