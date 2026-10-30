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

package org.unigrid.hedgehog.service.chain;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.DefaultSocks5CommandRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialRequest;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5ClientEncoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthResponseDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.unigrid.hedgehog.proxy.session.SocksAddress;

/* A SOCKS5 client of Netty's: greets with NO AUTH, or with USERNAME/PASSWORD when it names a username, asks to
   CONNECT, sends the bytes it was given right behind the request, as a client that does not wait for the reply
   would, and keeps what the destination sends */
final class SocksClient implements AutoCloseable {
	private static final int WAIT_SECONDS = 10;

	final CompletableFuture<Socks5CommandResponse> reply = new CompletableFuture<>();
	final CompletableFuture<Void> closed = new CompletableFuture<>();
	private final ByteArrayOutputStream received = new ByteArrayOutputStream();
	private final Channel channel;

	SocksClient(final EventLoopGroup group, final int port, final SocksAddress destination, final byte[] early)
		throws InterruptedException {

		this(group, port, destination, early, Optional.empty());
	}

	/* The password is not read by the proxy, so any will do */
	SocksClient(final EventLoopGroup group, final int port, final SocksAddress destination, final byte[] early,
		final Optional<String> username) throws InterruptedException {

		channel = new Bootstrap().group(group).channel(NioSocketChannel.class)
			.handler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(final SocketChannel channel) {
					channel.pipeline().addLast(Socks5ClientEncoder.DEFAULT,
						new Socks5InitialResponseDecoder(), new Reader(destination, early, username));
				}
			}).connect("127.0.0.1", port).sync().channel();
		channel.writeAndFlush(new DefaultSocks5InitialRequest(username.isPresent() ? Socks5AuthMethod.PASSWORD
			: Socks5AuthMethod.NO_AUTH));
	}

	Socks5CommandResponse reply() throws Exception {
		return reply.get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	void write(final byte[] bytes) {
		channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
	}

	/* What the destination has sent so far */
	byte[] received() {
		synchronized (received) {
			return received.toByteArray();
		}
	}

	void awaitClosed() throws Exception {
		closed.get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void close() {
		channel.close().syncUninterruptibly();
	}

	private final class Reader extends SimpleChannelInboundHandler<Object> {
		private final SocksAddress destination;
		private final byte[] early;
		private final Optional<String> username;

		private Reader(final SocksAddress destination, final byte[] early, final Optional<String> username) {
			super(false);
			this.destination = destination;
			this.early = early;
			this.username = username;
		}

		/* The request and the early bytes leave in one write, so the proxy reads them together */
		@Override
		protected void channelRead0(final ChannelHandlerContext ctx, final Object message) {
			if (message instanceof Socks5InitialResponse && username.isPresent()) {
				ctx.pipeline().replace(Socks5InitialResponseDecoder.class, "credentials",
					new Socks5PasswordAuthResponseDecoder());
				ctx.writeAndFlush(new DefaultSocks5PasswordAuthRequest(username.get(), "-"));
			} else if (message instanceof Socks5InitialResponse) {
				ctx.pipeline().replace(Socks5InitialResponseDecoder.class, "command",
					new Socks5CommandResponseDecoder());
				request(ctx);
			} else if (message instanceof Socks5PasswordAuthResponse response
				&& response.status() == Socks5PasswordAuthStatus.SUCCESS) {
				ctx.pipeline().replace(Socks5PasswordAuthResponseDecoder.class, "command",
					new Socks5CommandResponseDecoder());
				request(ctx);
			} else if (message instanceof Socks5CommandResponse response) {
				reply.complete(response);
			} else if (message instanceof ByteBuf bytes) {
				synchronized (received) {
					received.writeBytes(ByteBufUtil.getBytes(bytes));
				}

				bytes.release();
			}
		}

		private void request(final ChannelHandlerContext ctx) {
			ctx.write(new DefaultSocks5CommandRequest(Socks5CommandType.CONNECT, destination.type(),
				destination.host(), destination.port()));
			ctx.writeAndFlush(Unpooled.wrappedBuffer(early));
		}

		@Override
		public void channelInactive(final ChannelHandlerContext ctx) {
			closed.complete(null);
			reply.completeExceptionally(new IllegalStateException("The proxy closed the connection"));
			ctx.fireChannelInactive();
		}
	}
}
