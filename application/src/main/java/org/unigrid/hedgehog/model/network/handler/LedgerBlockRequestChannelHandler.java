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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;

@Sharable
public class LedgerBlockRequestChannelHandler extends AbstractLedgerHandler<LedgerBlockRequest> {
	public LedgerBlockRequestChannelHandler() {
		super(LedgerBlockRequest.class);
	}

	/* The driver answers on its own thread, so the reply is written from the callback. A request that it refuses
	   completes exceptionally and is left without a reply, as is one from a peer that does not read what it was
	   sent: a batch is megabytes, and a few requests in a row must not pile them up in memory. */
	@Override
	protected void deliver(final LedgerGateway gateway, final LedgerPeer from, final ChannelHandlerContext ctx,
		final LedgerBlockRequest packet) {

		gateway.onBlockRequest(from, packet.getRequest()).thenAccept(batch -> {
			if (ctx.channel().isWritable()) {
				ctx.writeAndFlush(LedgerBlockBatch.builder().requestId(packet.getRequestId()).batch(batch)
					.build()).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
			}
		});
	}
}
