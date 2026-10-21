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

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;

/* A handler of the ledger protocol only says which gateway method a packet goes to. The peer is the one of the
   stream, and a malformed packet never gets here: the decoder refuses it, and the stream is closed. So is a stream
   whose connection did not settle on the ledger protocol (the capability gate, see LedgerGateway): a node that cannot
   send the ledger at all, as one of an earlier release, sends none, so this is a peer that is not what it said. */
@Slf4j
public abstract class AbstractLedgerHandler<T extends Packet> extends AbstractInboundHandler<T> {
	static final AttributeKey<LedgerGateway> GATEWAY_KEY = AttributeKey.valueOf("LEDGER_GATEWAY");

	protected AbstractLedgerHandler(final Class<T> clazz) {
		super(clazz);
	}

	/* The gateway is looked up through CDI for the first packet of a stream only: the lookup is the costliest step
	   of a packet and is done before any limit is applied, so a peer that sends many cannot be allowed to make this
	   node repeat it for each one */
	@Override
	public void typedChannelRead(final ChannelHandlerContext ctx, final T packet) {
		final LedgerGateway held = ctx.channel().attr(GATEWAY_KEY).get();

		if (held != null) {
			handle(held, ctx, packet);
		} else {
			CDIUtil.resolveAndRun(LedgerGateway.class, gateway -> {
				ctx.channel().attr(GATEWAY_KEY).set(gateway);
				handle(gateway, ctx, packet);
			});
		}
	}

	private void handle(final LedgerGateway gateway, final ChannelHandlerContext ctx, final T packet) {
		if (gateway.speaks(ctx.channel())) {
			deliver(gateway, speakerOf(gateway, ctx), ctx, packet);
		} else {
			log.atDebug().addArgument(() -> ctx.channel().remoteAddress())
				.log("Closed a stream that sent the ledger without having agreed to it: {}");
			ctx.close();
		}
	}

	private static LedgerPeer speakerOf(final LedgerGateway gateway, final ChannelHandlerContext ctx) {
		return gateway.speakerOf(ctx.channel(), () -> RemoteAddress.of(ctx.channel()));
	}

	/* The size of the frame that the packet came in, which is what it takes of the queue of the driver while it
	   waits: the decoder of the frames has noted it for the packet that is being handled */
	protected static int sizeOf(final ChannelHandlerContext ctx) {
		final Integer size = ctx.channel().attr(FrameDecoder.PACKET_SIZE_KEY).get();

		return size == null ? 0 : size;
	}

	protected abstract void deliver(LedgerGateway gateway, LedgerPeer from, ChannelHandlerContext ctx, T packet);
}
