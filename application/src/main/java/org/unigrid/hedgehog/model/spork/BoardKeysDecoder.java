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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.chunk.Chunk;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.TypedCodec;

/* Every count is held to its cap before anything it counts is read, so a peer cannot make a node read more than a full
   chain of full changes before a signature is checked */
@Chunk(type = ChunkType.DECODER, group = ChunkGroup.GRIDSPORK)
public class BoardKeysDecoder implements TypedCodec<GridSpork.Type>, ChunkDecoder<BoardKeys.SporkData> {
	private static final int MAX_KEY_LENGTH = 2 * Signature.PUBLIC_KEY_HEX_SIZE;

	/*
	    Chunk format:
	    0..............................................................63
	    [         << Spork Header (AbstractGridSporkDecoder) >>        ]
	    [n=changes]
	   n[                   << change (KeyChange) >>                   ]
	*/
	@Override
	public Optional<BoardKeys.SporkData> decodeChunk(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final int count = capped(in.readUnsignedByte(), BoardKeyChain.MAX_CHANGES,
			"A chain of %d changes of the board keys");
		final BoardKeys.SporkData data = new BoardKeys.SporkData();

		for (int i = 0; i < count; i++) {
			data.getChanges().add(change(in));
		}

		return Optional.of(data);
	}

	private static KeyChange change(ByteBuf in) {
		final KeyChange change = new KeyChange();
		final byte[] previous = new byte[KeyChange.DIGEST_SIZE];

		change.setSequence(in.readInt());
		change.setMadeAt(Instant.ofEpochMilli(in.readLong()));
		in.readBytes(previous);
		change.setPrevious(previous);

		final int keys = capped(in.readUnsignedByte(), BoardKeyChain.MAX_KEYS,
			"A change of the board keys with %d keys");

		for (int i = 0; i < keys; i++) {
			change.getActive().add(key(in));
		}

		final int approvals = capped(in.readUnsignedByte(), BoardKeyChain.MAX_APPROVALS,
			"A change of the board keys with %d approvals");

		for (int i = 0; i < approvals; i++) {
			change.getApprovals().add(new KeyChange.Approval(key(in), signature(in)));
		}

		return change;
	}

	private static String key(ByteBuf in) {
		final int length = capped(in.readUnsignedShort(), MAX_KEY_LENGTH,
			"A key of %d characters in a change of the board keys");

		return in.readCharSequence(length, StandardCharsets.US_ASCII).toString();
	}

	private static byte[] signature(ByteBuf in) {
		final byte[] signature = new byte[capped(in.readUnsignedShort(), KeyChange.MAX_SIGNATURE_SIZE,
			"A signature of %d bytes in a change of the board keys")];

		in.readBytes(signature);
		return signature;
	}

	private static int capped(int count, int cap, String what) {
		if (count > cap) {
			throw new CorruptedFrameException(what.formatted(count) + " exceeds the cap of " + cap);
		}

		return count;
	}

	@Override
	public GridSpork.Type getCodecType() {
		return GridSpork.Type.BOARD_KEYS;
	}
}
