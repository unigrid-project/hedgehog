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
	package org.unigrid.hedgehog.model.network.codec;

	import io.netty.buffer.ByteBuf;
	import io.netty.channel.ChannelHandlerContext;
	import java.lang.reflect.Field;
	import java.time.Instant;
	import java.util.Optional;
	
	import org.unigrid.hedgehog.model.collection.OptionalMap;
	import org.unigrid.hedgehog.model.spork.GridSpork;
	import org.unigrid.hedgehog.model.network.packet.Packet;
	import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;
	import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
	import org.unigrid.hedgehog.model.network.chunk.ChunkType;
	import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
	
	/**
	 * GridSpork-dekodning med reflection, kompatibel med generiska Chunk-systemet.
	 */
	public abstract class AbstractGridSporkDecoder<T extends Packet> extends AbstractReplayingDecoder<T> {
	
		private final OptionalMap<GridSpork.Type, ChunkDecoder<?>> decoders;
	
		@SuppressWarnings("unchecked")
		protected AbstractGridSporkDecoder() {
			// Unchecked cast p.g.a. generics i ChunkScanner.scan()
			decoders = (OptionalMap<GridSpork.Type, ChunkDecoder<?>>)
					   (OptionalMap<?, ?>) ChunkScanner.scan(ChunkType.DECODER, ChunkGroup.GRIDSPORK);
		}
	
		public Optional<GridSpork> decodeGridSpork(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
			final GridSpork.Type type = GridSpork.Type.get(in.readShort());
			final Optional<ChunkDecoder<?>> cd = decoders.getOptional(type);
	
			if (!cd.isPresent()) {
				System.err.println("Unable to handle spork chunk of type " + type);
				return Optional.empty();
			}
	
			final GridSpork gridSpork = GridSpork.create(type);
	
			// --- reflection: skriv fälten direkt ---
			setPrivate(gridSpork, "flags", in.readShort());
			in.skipBytes(4); // 32 bits reserved
			setPrivate(gridSpork, "timeStamp", Instant.ofEpochMilli(in.readLong()));
			setPrivate(gridSpork, "previousTimeStamp", Instant.ofEpochMilli(in.readLong()));
			in.skipBytes(8); // 64 bits reserved
	
			// --- dekoda sporkdata och delta via reflection ---
			Object decodedData = invokeDecode(cd.get(), ctx, in);
			Object decodedPrevData = invokeDecode(cd.get(), ctx, in);
	
			setPrivate(gridSpork, "data", decodedData);
			setPrivate(gridSpork, "previousData", decodedPrevData);
	
			// --- signatur ---
			int signatureLength = in.readUnsignedShort();
			byte[] signature = new byte[signatureLength];
			in.readBytes(signature);
			setPrivate(gridSpork, "signature", signature);
	
			return Optional.of(gridSpork);
		}
	
		// =========================
		// Helpers
		// =========================
	
		private static Object invokeDecode(ChunkDecoder<?> codec, ChannelHandlerContext ctx, ByteBuf in) throws Exception {
			// Hitta "decodeChunk" metod via reflection
			Optional<?> result = (Optional<?>) codec.getClass()
					.getMethod("decodeChunk", ChannelHandlerContext.class, ByteBuf.class)
					.invoke(codec, ctx, in);
	
			return result.orElse(null);
		}
	
		private static void setPrivate(Object target, String fieldName, Object value) throws Exception {
			Field f = target.getClass().getDeclaredField(fieldName);
			f.setAccessible(true);
			f.set(target, value);
		}
	}
	