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
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;

@Slf4j
public class GridnodeDecoder extends AbstractReplayingDecoder<PublishGridnode> implements PacketDecoder<PublishGridnode> {
	public static final int ID_LENGTH = Signature.PUBLIC_KEY_HEX_SIZE * 2;
	public static final int MAX_HOST_LENGTH = 255;
	public static final int MAX_SIGNATURE_LENGTH = 150;

	@Override
	public Optional<PublishGridnode> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
		final Gridnode.Status status = Gridnode.Status.get(in.readByte());
		final long timestamp = in.readLong();
		final byte[] id = readField(in, ID_LENGTH);
		final byte[] host = readField(in, MAX_HOST_LENGTH);
		final byte[] signature = readField(in, MAX_SIGNATURE_LENGTH);

		if (id.length != ID_LENGTH) {
			throw new CorruptedFrameException("A gridnode id is " + ID_LENGTH + " bytes, not " + id.length);
		}

		log.atDebug().log("decode gridnode");

		return Optional.of(PublishGridnode.builder().gridnode(Gridnode.builder()
			.id(new String(id, StandardCharsets.UTF_8)).hostName(new String(host, StandardCharsets.UTF_8))
			.status(status).timestamp(timestamp).signature(signature).build()).build());
	}

	/* The limit is checked before the allocation so a hostile length cannot make the node reserve memory */
	private static byte[] readField(ByteBuf in, int maxLength) {
		final int length = in.readUnsignedShort();

		if (length > maxLength) {
			throw new CorruptedFrameException("A gridnode field of " + length + " bytes exceeds " + maxLength);
		}

		final byte[] field = new byte[length];

		in.readBytes(field);
		return field;
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.GRIDNODE;
	}
}
