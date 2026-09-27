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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
import org.unigrid.hedgehog.model.spork.GridSpork;

/*
 * The part of a spork its signatures cover, encoded exactly as it travels: field by field, so the bytes
 * depend only on the content and every node computes the same ones.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkContentEncoder {
	/* Scanning the classpath is expensive, so it happens once rather than per connection or signature */
	private static final class Encoders {
		private static final OptionalMap<GridSpork.Type, ChunkEncoder> INSTANCE = ChunkScanner.scan(
			ChunkType.ENCODER, ChunkGroup.GRIDSPORK
		);
	}

	public static boolean canEncode(GridSpork.Type type) {
		return Encoders.INSTANCE.getOptional(type).isPresent();
	}

	/*
	    Content format:
	    0..............................................................63
	    [  spork type  ][    flags     ][           reserved           ]
	    [                           timestamp                          ]
	    [                      previous timestamp                      ]
	    [                           reserved                           ]
	    [                       << spork data >>                       ]
	    [                    << spork delta data >>                    ]
	*/
	public static void encode(GridSpork spork, ByteBuf out) throws Exception {
		final Optional<ChunkEncoder> encoder = Encoders.INSTANCE.getOptional(spork.getType());

		if (encoder.isEmpty()) {
			throw new IllegalArgumentException("Unable to encode a spork of type " + spork.getType());
		}

		out.writeShort(spork.getType().getValue());
		out.writeShort(spork.getFlags());
		out.writeZero(4 /* 32 bits */);
		out.writeLong(Objects.requireNonNullElse(spork.getTimeStamp(), Instant.EPOCH).toEpochMilli());
		out.writeLong(Objects.requireNonNullElse(spork.getPreviousTimeStamp(), Instant.EPOCH).toEpochMilli());
		out.writeZero(8 /* 64 bits */);

		encoder.get().encodeChunk(null, orEmpty(spork, spork.getData()), out);
		encoder.get().encodeChunk(null, orEmpty(spork, spork.getPreviousData()), out);
	}

	@SneakyThrows
	public static byte[] encode(GridSpork spork) {
		final ByteBuf out = Unpooled.buffer();

		try {
			encode(spork, out);
			return ByteBufUtil.getBytes(out);
		} finally {
			out.release();
		}
	}

	/* A spork that was never archived has no previous data; it travels, and is signed, as empty data */
	private static ChunkData orEmpty(GridSpork spork, ChunkData data) {
		return Objects.requireNonNullElseGet(data,
			() -> GridSpork.create(spork.getType()).<ChunkData>getData().empty()
		);
	}
}
