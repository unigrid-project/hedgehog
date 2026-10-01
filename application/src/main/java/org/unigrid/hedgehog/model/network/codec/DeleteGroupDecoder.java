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
import java.util.Optional;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.DeleteGroup;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.storage.GroupKey;

public class DeleteGroupDecoder extends AbstractReplayingDecoder<DeleteGroup>
	implements PacketDecoder<DeleteGroup> {

	@Override
	public Optional<DeleteGroup> typedDecode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
		return Optional.of(DeleteGroup.builder().requestId(in.readLong()).groupId(StorageCodecs.readGroupId(in))
			.publicKey(StorageCodecs.readBytes(in, GroupKey.PUBLIC_KEY_SIZE)).timestamp(in.readLong())
			.signature(StorageCodecs.readBytes(in, GroupKey.SIGNATURE_SIZE)).build());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.DELETE_GROUP;
	}
}
