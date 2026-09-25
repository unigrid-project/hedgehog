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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.SignatureLog;
import org.unigrid.hedgehog.model.spork.SignatureLogEntry;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.codec.api.ChunkDecoder;

@Slf4j
public abstract class AbstractGridSporkDecoder<T extends Packet> extends AbstractReplayingDecoder<T> {

	private final OptionalMap<GridSpork.Type, ChunkDecoder> decoders;

	protected AbstractGridSporkDecoder() {
		decoders = ChunkScanner.scan(ChunkType.DECODER, ChunkGroup.GRIDSPORK);
	}

	/*
	    Packet format:
	    0..............................................................63
	    [                << Frame Header (FrameDecoder) >>             ]
	    [     type     ][    flags     ][           reserved           ]
            [                           timestamp                          ]
	    [                      previous timpestamp                     ]
	    [                           reserved                           ]
	    [                       << spork data >>                       ]
	    [                    << spork delta data >>                    ]
	    [     size     ][             signature (size long)          >>]
	    [     size     ][   cosignature (size long, 0 while pending) >>]
	    [      signature log entries       ][   << log entries >>      >>]

	    Signature log entry:
	    [                    signed version timestamp                  ]
	    [     size     ][            signer public key (size long)   >>]
	    [                    << SHA-512 digest (64 bytes) >>           ]
	    [     size     ][             signature (size long)          >>]
	    [     size     ][   cosigner public key (size long, 0 if none) >>]
	    [     size     ][       cosignature (size long, 0 if none)   >>]
	*/
	public Optional<GridSpork> decodeGridSpork(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final GridSpork.Type type = GridSpork.Type.get(in.readShort());
		final Optional<ChunkDecoder> cd = decoders.getOptional(type);

		if (cd.isPresent()) {
			log.atTrace().log("decoding spork chunk of type {}", type);
			final GridSpork gridSpork = GridSpork.create(type);

			gridSpork.setFlags(in.readShort());
			in.skipBytes(4 /* 32 bits */);
			gridSpork.setTimeStamp(Instant.ofEpochMilli(in.readLong()));
			gridSpork.setPreviousTimeStamp(Instant.ofEpochMilli(in.readLong()));
			in.skipBytes(8 /* 64 bits */);

			gridSpork.setData((ChunkData) cd.get().decodeChunk(ctx, in).get());
			gridSpork.setPreviousData((ChunkData) cd.get().decodeChunk(ctx, in).get());

			gridSpork.setSignature(readSized(in));
			gridSpork.setCosignature(emptyAsNull(readSized(in)));

			final int logSize = in.readInt();
			final List<SignatureLogEntry> entries = new ArrayList<>();

			for (int i = 0; i < logSize; i++) {
				entries.add(decodeLogEntry(in));
			}

			gridSpork.setSignatureLog(new SignatureLog(entries));
			return Optional.of(gridSpork);
		}

		log.atError().log("Unable to handle spork chunk of type {}", type);
		return Optional.empty();
	}

	private SignatureLogEntry decodeLogEntry(ByteBuf in) {
		final Instant timeStamp = Instant.ofEpochMilli(in.readLong());
		final String signer = in.readCharSequence(in.readUnsignedShort(), StandardCharsets.US_ASCII).toString();
		final byte[] digest = new byte[SignatureLogEntry.DIGEST_SIZE];

		in.readBytes(digest);

		final byte[] signature = readSized(in);
		final String cosigner = in.readCharSequence(in.readUnsignedShort(), StandardCharsets.US_ASCII).toString();
		final byte[] cosignature = readSized(in);

		return SignatureLogEntry.builder().timeStamp(timeStamp).signer(signer).digest(digest).signature(signature)
			.cosigner(cosigner.isEmpty() ? null : cosigner).cosignature(emptyAsNull(cosignature)).build();
	}

	private static byte[] readSized(ByteBuf in) {
		final byte[] bytes = new byte[in.readUnsignedShort()];

		in.readBytes(bytes);
		return bytes;
	}

	private static byte[] emptyAsNull(byte[] bytes) {
		return bytes.length == 0 ? null : bytes;
	}
}
