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
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupId;
import org.unigrid.hedgehog.model.storage.GroupKey;

public class FragmentStatusDecoder extends AbstractReplayingDecoder<FragmentStatus>
	implements PacketDecoder<FragmentStatus> {

	private static FragmentStatus.Entry readEntry(final ByteBuf in) {
		final GroupId groupId = StorageCodecs.readGroupId(in);
		final FragmentStatus.State state = FragmentStatus.State.of(in.readUnsignedByte());
		final int index = in.readUnsignedByte();

		return new FragmentStatus.Entry(groupId, state, index,
			state == FragmentStatus.State.TOMBSTONE ? readProof(in) : null);
	}

	private static DeleteProof readProof(final ByteBuf in) {
		final byte[] publicKey = new byte[GroupKey.PUBLIC_KEY_SIZE];
		final byte[] signature = new byte[GroupKey.SIGNATURE_SIZE];

		in.readBytes(publicKey);
		final long timestamp = in.readLong();
		in.readBytes(signature);
		return new DeleteProof(publicKey, timestamp, signature);
	}

	@Override
	public Optional<FragmentStatus> typedDecode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
		final long requestId = in.readLong();
		final List<FragmentStatus.Entry> entries = IntStream.range(0, StorageCodecs.readCount(in))
			.mapToObj(i -> readEntry(in)).collect(Collectors.toList());

		return Optional.of(FragmentStatus.builder().requestId(requestId).entries(entries).build());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.FRAGMENT_STATUS;
	}
}
