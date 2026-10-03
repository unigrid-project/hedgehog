/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation, UGD Software AB

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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;
import org.unigrid.hedgehog.model.network.packet.FragmentStatus;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.storage.DeleteProof;

@Sharable
public class FragmentStatusEncoder extends AbstractMessageToByteEncoder<FragmentStatus>
	implements PacketEncoder<FragmentStatus> {

	private static final String MALFORMED_PROOF = "A tombstone needs a proof with a 32-byte key and a 64-byte signature";

	/*
	 * [request id u64][count u16]([group id 32 bytes][state u8][fragment index u8][proof]) * count, where only a
	 * tombstone has a proof: [public key 32 bytes][timestamp u64][signature 64 bytes]
	 */
	@Override
	public Optional<ByteBuf> encode(final ChannelHandlerContext ctx, final FragmentStatus packet) throws Exception {
		final ByteBuf out = Unpooled.buffer();

		out.writeLong(packet.getRequestId());
		StorageCodecs.writeCount(out, packet.getEntries().size());

		for (final FragmentStatus.Entry entry : packet.getEntries()) {
			StorageCodecs.writeGroupId(out, entry.getGroupId());
			out.writeByte(entry.getState().ordinal());
			StorageCodecs.writeIndex(out, entry.getIndex());

			if (entry.getState() == FragmentStatus.State.TOMBSTONE) {
				writeProof(out, entry);
			}
		}

		return Optional.of(out);
	}

	private static void writeProof(final ByteBuf out, final FragmentStatus.Entry tombstone) {
		final DeleteProof proof = tombstone.getProof().filter(DeleteProof::isWellFormed)
			.orElseThrow(() -> new IllegalArgumentException(MALFORMED_PROOF));

		out.writeBytes(proof.getPublicKey()).writeLong(proof.getTimestamp()).writeBytes(proof.getSignature());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.FRAGMENT_STATUS;
	}
}
