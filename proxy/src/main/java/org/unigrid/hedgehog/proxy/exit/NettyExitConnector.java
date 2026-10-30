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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/* Connects over TCP with Netty on an NIO event loop group, trying the admitted addresses one after another. Given a
   local address, every connection leaves from it, so the destination sees the address it was given. */
public final class NettyExitConnector implements ExitConnector {
	/* Each unreachable address costs a whole connect timeout, and whoever controls a name controls how many
	   addresses it has, so a stranger could keep a relay connecting for minutes. Names seldom have more. */
	public static final int MAX_ADDRESSES = 8;

	/* What a close waits for the destination to take, after which the connection goes whatever is left. The
	   relay is gone by then, so a destination that never reads must not hold the socket for ever. */
	static final int CLOSE_GRACE_MILLIS = 5000;

	private final EventLoopGroup group;
	private final int closeGraceMillis;
	private final Bootstrap bootstrap;
	private final Predicate<InetAddress> reachable;

	public NettyExitConnector(EventLoopGroup group, int connectTimeoutMillis) {
		this(group, connectTimeoutMillis, Optional.empty());
	}

	public NettyExitConnector(EventLoopGroup group, int connectTimeoutMillis, Optional<InetAddress> local) {
		this(group, connectTimeoutMillis, CLOSE_GRACE_MILLIS, local);
	}

	NettyExitConnector(EventLoopGroup group, int connectTimeoutMillis, int closeGraceMillis) {
		this(group, connectTimeoutMillis, closeGraceMillis, Optional.empty());
	}

	NettyExitConnector(EventLoopGroup group, int connectTimeoutMillis, int closeGraceMillis,
		Optional<InetAddress> local) {

		if (connectTimeoutMillis <= 0) {
			throw new IllegalArgumentException("The connect timeout must be positive: " + connectTimeoutMillis);
		}

		this.group = group;
		this.closeGraceMillis = closeGraceMillis;
		bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
			.option(ChannelOption.AUTO_READ, false).option(ChannelOption.TCP_NODELAY, true);

		Objects.requireNonNull(local, "local").ifPresent(address -> bootstrap.localAddress(address, 0));
		reachable = AddressFamily.reachableFrom(local);
	}

	/* The list is copied because the attempts go on after this call, on the event loop, while the caller may
	   reuse it. A null list or element is the caller's fault and throws before any listener call. Given a local
	   address, only the addresses of its family are tried, and only they count towards the limit. */
	@Override
	public Attempt connect(List<InetAddress> addresses, int port, TargetListener listener) {
		final Dialing dialing = new Dialing(List.copyOf(addresses).stream().filter(reachable).limit(MAX_ADDRESSES)
			.iterator(), port, listener);

		dialing.next(Socks5CommandStatus.HOST_UNREACHABLE);
		return dialing;
	}

	/* The SOCKS5 reply for a failed connection attempt */
	static Socks5CommandStatus statusOf(Throwable cause) {
		if (cause instanceof ConnectTimeoutException) {
			return Socks5CommandStatus.TTL_EXPIRED;
		}

		if (cause instanceof NoRouteToHostException) {
			return Socks5CommandStatus.HOST_UNREACHABLE;
		}

		if (cause instanceof ConnectException) {
			return Socks5CommandStatus.CONNECTION_REFUSED;
		}

		/* What is left of the socket errors of a connect is an unreachable network or address family */
		return cause instanceof SocketException ? Socks5CommandStatus.NETWORK_UNREACHABLE
			: Socks5CommandStatus.FAILURE;
	}

	/* One connect: the addresses left to try and the channel being dialled, which goes either to the listener or to
	   a cancel, never to both */
	private final class Dialing implements Attempt {
		private final Iterator<InetAddress> addresses;
		private final int port;
		private final TargetListener listener;
		private final AtomicReference<Channel> dialled = new AtomicReference<>();
		private volatile boolean cancelled;

		Dialing(Iterator<InetAddress> addresses, int port, TargetListener listener) {
			this.addresses = addresses;
			this.port = port;
			this.listener = listener;
		}

		@Override
		public void cancel() {
			cancelled = true;

			final Channel channel = dialled.getAndSet(null);

			if (channel != null) {
				channel.close();
			}
		}

		private void next(Socks5CommandStatus last) {
			if (cancelled) {
				return;
			}

			if (group.isShuttingDown()) {
				listener.failed(Socks5CommandStatus.FAILURE);
			} else if (addresses.hasNext()) {
				dial(addresses.next());
			} else {
				listener.failed(last);
			}
		}

		private void dial(InetAddress address) {
			final TargetHandler handler = new TargetHandler();
			final ChannelFuture connecting = bootstrap.clone().handler(handler)
				.connect(new InetSocketAddress(address, port));

			dialled.set(connecting.channel());

			/* A cancel that came after the check in next found no channel to close */
			if (cancelled) {
				cancel();
			}

			connecting.addListener((ChannelFutureListener) done -> settled(done, handler));
		}

		/* A channel that failed to connect is closed already; one that connected may have been taken by a cancel */
		private void settled(ChannelFuture done, TargetHandler handler) {
			if (!done.isSuccess()) {
				next(statusOf(done.cause()));
			} else if (dialled.compareAndSet(done.channel(), null)) {
				handler.listener = listener;
				listener.connected(new NettyTarget(done.channel()));
			}
		}
	}

	private final class NettyTarget implements Target {
		private final Channel channel;

		NettyTarget(Channel channel) {
			this.channel = channel;
		}

		@Override
		public InetSocketAddress localAddress() {
			return (InetSocketAddress) channel.localAddress();
		}

		@Override
		public void write(byte[] bytes) {
			channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
		}

		@Override
		public long backlogBytes() {
			final ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();

			return buffer == null ? 0 : buffer.totalPendingWriteBytes();
		}

		@Override
		public void setReadable(boolean readable) {
			channel.config().setAutoRead(readable);
		}

		@Override
		public void close() {
			if (channel.isOpen()) {
				channel.config().setAutoRead(false);
				channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);

				final ScheduledFuture<?> deadline = channel.eventLoop().schedule(() -> {
					channel.close();
				}, closeGraceMillis, TimeUnit.MILLISECONDS);

				channel.closeFuture().addListener(closed -> deadline.cancel(false));
			}
		}
	}

	private static final class TargetHandler extends ChannelInboundHandlerAdapter {
		/* Set on the event loop once the connection is handed over, as a cancel closes one that never was. Nothing
		   is read before, since reads start off. */
		private TargetListener listener;

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object message) {
			try {
				if (message instanceof ByteBuf bytes) {
					listener.received(ByteBufUtil.getBytes(bytes));
				}
			} finally {
				ReferenceCountUtil.release(message);
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			if (listener != null) {
				listener.closed();
			}

			ctx.fireChannelInactive();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.close();
		}
	}
}
