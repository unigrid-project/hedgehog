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
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.util.Optional;
import java.util.function.BiPredicate;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;

public class LedgerBlockBatchDecoder extends AbstractReplayingDecoder<LedgerBlockBatch>
	implements PacketDecoder<LedgerBlockBatch> {

	private final BiPredicate<Channel, Long> awaited;

	public LedgerBlockBatchDecoder() {
		this(LedgerBlockBatchDecoder::isAwaited);
	}

	public LedgerBlockBatchDecoder(final BiPredicate<Channel, Long> awaited) {
		this.awaited = awaited;
	}

	private static boolean isAwaited(final Channel channel, final long requestId) {
		final Instance<PendingRequests> pending = CDI.current().select(PendingRequests.class);

		return pending.isResolvable() && pending.get().awaits(channel, requestId, LedgerBlockBatch.class);
	}

	/* A batch is megabytes, and only a peer that was asked sends one. One that nobody awaits, late or unasked, is
	   left whole before any of it is read, as a peer can send as many as it likes. */
	@Override
	public Optional<LedgerBlockBatch> typedDecode(final ChannelHandlerContext ctx, final ByteBuf in)
		throws Exception {

		final long requestId = in.readLong();

		if (!awaited.test(ctx.channel(), requestId)) {
			return Optional.empty();
		}

		final byte[] bytes = LedgerCodecs.readBytes(in, LedgerCodecs.MAX_BATCH_SIZE);

		return Optional.of(LedgerBlockBatch.builder().requestId(requestId)
			.batch(WireCodec.decodeBlockBatch(bytes)).build());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.LEDGER_BLOCK_BATCH;
	}
}
