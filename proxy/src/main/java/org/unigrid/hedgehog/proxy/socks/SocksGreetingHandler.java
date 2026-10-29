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
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.util.ReferenceCountUtil;

/* Answers the SOCKS5 greeting. USERNAME/PASSWORD is preferred when the client offers it, because the username is
   how a client names the country of its exit; otherwise NO AUTH is served. The listener binds to loopback unless
   its operator says otherwise, so neither method grants anything: a client that offers neither is told so and
   disconnected. */
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
		} else if (request.authMethods().contains(Socks5AuthMethod.PASSWORD)) {
			answer(ctx, Socks5AuthMethod.PASSWORD);
		} else if (request.authMethods().contains(Socks5AuthMethod.NO_AUTH)) {
			/* A client that sends no credentials sends its command next, which their decoder must not read */
			ctx.pipeline().remove(SocksPasswordHandler.class);
			ctx.pipeline().remove(Socks5PasswordAuthRequestDecoder.class);
			answer(ctx, Socks5AuthMethod.NO_AUTH);
		} else {
			ctx.channel().config().setAutoRead(false);
			ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.UNACCEPTED))
				.addListener(ChannelFutureListener.CLOSE);
		}
	}

	/* Both leave the pipeline: what follows the greeting in the same read goes on to the next decoder */
	private void answer(ChannelHandlerContext ctx, Socks5AuthMethod method) {
		ctx.writeAndFlush(new DefaultSocks5InitialResponse(method));
		ctx.pipeline().remove(this);
		ctx.pipeline().remove(Socks5InitialRequestDecoder.class);
	}
}
