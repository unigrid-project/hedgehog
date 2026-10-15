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
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;

/* A handler of the ledger protocol only says which gateway method a packet goes to. The peer is the one of the
   stream, and a malformed packet never gets here: the decoder refuses it, and the stream is closed. */
public abstract class AbstractLedgerHandler<T extends Packet> extends AbstractInboundHandler<T> {
	protected AbstractLedgerHandler(final Class<T> clazz) {
		super(clazz);
	}

	@Override
	public void typedChannelRead(final ChannelHandlerContext ctx, final T packet) {
		CDIUtil.resolveAndRun(LedgerGateway.class, gateway -> deliver(gateway,
			gateway.speakerOf(ctx.channel(), () -> RemoteAddress.of(ctx.channel())), ctx, packet));
	}

	protected abstract void deliver(LedgerGateway gateway, LedgerPeer from, ChannelHandlerContext ctx, T packet);
}
