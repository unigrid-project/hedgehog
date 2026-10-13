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

package org.unigrid.hedgehog.proxy.socks;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.util.ReferenceCountUtil;

/* Answers the SOCKS5 greeting. Only NO AUTH is offered, because the listener binds to loopback unless its
   operator says otherwise; a client that insists on authentication is told so and disconnected. */
final class SocksGreetingHandler extends ChannelInboundHandlerAdapter {
	@Override
	public void channelRead(ChannelHandlerContext ctx, Object message) {
		if (message instanceof Socks5InitialRequest request) {
			greet(ctx, request);
		} else {
			/* Bytes behind a refused greeting must never reach the command decoder */
			ReferenceCountUtil.release(message);
		}
	}

	private void greet(ChannelHandlerContext ctx, Socks5InitialRequest request) {
		if (request.decoderResult().isFailure()) {
			ctx.close();
		} else if (request.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
			ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.NO_AUTH));

			/* Both leave the pipeline: what follows the greeting in the same read goes to the command decoder */
			ctx.pipeline().remove(this);
			ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
		} else {
			ctx.channel().config().setAutoRead(false);
			ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
				.addListener(ChannelFutureListener.CLOSE);
		}
	}
}
