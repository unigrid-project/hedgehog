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
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.util.Optional;

/* Answers the username and password of a SOCKS5 client. The proxy has no accounts: the username names the country
   the client wants its exit in, and an empty one names none. The password is not read for anything, so a client
   may send any. A username that is no country is refused and the client is disconnected. */
final class SocksPasswordHandler extends ChannelInboundHandlerAdapter {
	/* The country the client named, upper case; for a client that named none, the one the listener serves by
	   default, and not set when there is none */
	static final AttributeKey<String> COUNTRY = AttributeKey.valueOf(SocksPasswordHandler.class, "country");

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object message) {
		if (message instanceof Socks5PasswordAuthRequest request) {
			authenticate(ctx, request);
		} else {
			/* Bytes behind a refused username must never reach the command decoder */
			ReferenceCountUtil.release(message);
		}
	}

	private void authenticate(ChannelHandlerContext ctx, Socks5PasswordAuthRequest request) {
		if (request.decoderResult().isFailure()) {
			ctx.close();
			return;
		}

		try {
			accept(ctx, Country.of(request.username()));
		} catch (IllegalArgumentException ex) {
			ctx.channel().config().setAutoRead(false);
			ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE))
				.addListener(ChannelFutureListener.CLOSE);
		}
	}

	private void accept(ChannelHandlerContext ctx, Optional<String> country) {
		country.ifPresent(code -> ctx.channel().attr(COUNTRY).set(code));
		ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));

		/* Both leave the pipeline: what follows the credentials in the same read goes to the command decoder */
		ctx.pipeline().remove(this);
		ctx.pipeline().remove(Socks5PasswordAuthRequestDecoder.class);
	}
}
