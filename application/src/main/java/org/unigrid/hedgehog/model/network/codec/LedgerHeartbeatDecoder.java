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
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;

public class LedgerHeartbeatDecoder extends AbstractReplayingDecoder<LedgerHeartbeat>
	implements PacketDecoder<LedgerHeartbeat> {

	@Override
	public Optional<LedgerHeartbeat> typedDecode(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {
		final byte[] bytes = LedgerCodecs.readFixed(in, WireCodec.HEARTBEAT_SIZE);

		return Optional.of(LedgerHeartbeat.builder().heartbeat(WireCodec.decodeHeartbeat(bytes)).build());
	}

	@Override
	public Packet.Type getCodecType() {
		return Packet.Type.LEDGER_HEARTBEAT;
	}
}
