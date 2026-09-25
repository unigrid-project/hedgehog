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

import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.incubator.codec.quic.QuicConnectionCloseEvent;
import io.netty.incubator.codec.quic.QuicConnectionEvent;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import javax.net.ssl.SSLHandshakeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.model.Network;

/**
* Names the peer when a QUIC handshake fails because the two nodes share no protocol, which is what a
* node of an incompatible release looks like. Without it, the server only sees a failed handshake and the
* client only sees its connection attempt time out.
*/
@Slf4j
@Sharable
public class ProtocolMismatchHandler extends ChannelInboundHandlerAdapter {
	private static final String NO_APPLICATION_PROTOCOL = "NO_APPLICATION_PROTOCOL";
	private static final int NO_APPLICATION_PROTOCOL_ALERT = 120;
	private static final String REFUSED_PEER = "Refused a connection from {}: it speaks none of our protocols {}, so it "
		+ "runs an incompatible release";
	private static final String REFUSED_BY_PEER = "{} refused the connection: it speaks none of our protocols {}, so it "
		+ "runs an incompatible release";

	public static final AttributeKey<SocketAddress> PEER_ADDRESS_KEY = AttributeKey.valueOf("PEER_ADDRESS");

	@Override
	public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
		if (evt instanceof QuicConnectionEvent event) {
			ctx.channel().attr(PEER_ADDRESS_KEY).setIfAbsent(event.newAddress());
		} else if (evt instanceof SslHandshakeCompletionEvent event && isProtocolMismatch(event.cause())) {
			warn(REFUSED_PEER, ctx);
		} else if (evt instanceof QuicConnectionCloseEvent event && isProtocolMismatch(event)) {
			warn(REFUSED_BY_PEER, ctx);
		}

		super.userEventTriggered(ctx, evt);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		/* The handshake event that carries the same failure has already been reported */
		if (!isProtocolMismatch(cause)) {
			super.exceptionCaught(ctx, cause);
		}
	}

	private static boolean isProtocolMismatch(Throwable cause) {
		return cause instanceof SSLHandshakeException
			&& StringUtils.contains(cause.getMessage(), NO_APPLICATION_PROTOCOL);
	}

	private static boolean isProtocolMismatch(QuicConnectionCloseEvent event) {
		return event.isTlsError()
			&& QuicConnectionCloseEvent.extractTlsError(event.error()) == NO_APPLICATION_PROTOCOL_ALERT;
	}

	private static void warn(String message, ChannelHandlerContext ctx) {
		final String protocols = String.join(", ", Network.getProtocols());

		log.atWarn().log(message, ctx.channel().attr(PEER_ADDRESS_KEY).get(), protocols);
	}
}
