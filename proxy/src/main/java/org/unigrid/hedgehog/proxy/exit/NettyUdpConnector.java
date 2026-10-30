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
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/* Datagram sockets with Netty on an NIO event loop group. Each is bound to an ephemeral port of the local address
   it is given, so the destination sees that address. Without one it is the wildcard address, because the exit
   does not know which interface a destination is reached from. It reads into buffers of the largest UDP
   datagram: Netty's default would cut a longer one without a word. */
public final class NettyUdpConnector implements UdpConnector {
	private static final int LARGEST_DATAGRAM = 64 * 1024;

	private final Supplier<EventLoopGroup> group;
	private final SocketAddress bound;
	private final Predicate<InetAddress> reachable;

	public NettyUdpConnector(EventLoopGroup group) {
		this(() -> group);
	}

	public NettyUdpConnector(Supplier<EventLoopGroup> group) {
		this(group, Optional.empty());
	}

	/* The group may be handed over lazily, so that a node which never relays a datagram starts no event loops */
	public NettyUdpConnector(Supplier<EventLoopGroup> group, Optional<InetAddress> local) {
		this.group = Objects.requireNonNull(group, "group");
		bound = Objects.requireNonNull(local, "local").<SocketAddress>map(
			address -> new InetSocketAddress(address, 0)).orElseGet(() -> new InetSocketAddress(0));
		reachable = AddressFamily.reachableFrom(local);
	}

	/* A group that is shut down would drop the answer to the bind, and the relay would wait for it for good */
	@Override
	public void open(Listener listener) {
		final EventLoopGroup loops = group.get();

		if (loops == null || loops.isShuttingDown()) {
			listener.failed(Socks5CommandStatus.FAILURE);
		} else {
			new Bootstrap().group(loops).channel(NioDatagramChannel.class)
				.option(ChannelOption.AUTO_READ, false)
				.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(LARGEST_DATAGRAM))
				.handler(new Receiver(listener)).bind(bound).addListener((ChannelFutureListener) done -> {
					if (done.isSuccess()) {
						listener.opened(new NettySocket(done.channel(), reachable));
					} else {
						listener.failed(Socks5CommandStatus.FAILURE);
					}
				});
		}
	}

	private static final class NettySocket implements Socket {
		private final Channel channel;
		private final Predicate<InetAddress> reachable;

		NettySocket(Channel channel, Predicate<InetAddress> reachable) {
			this.channel = channel;
			this.reachable = reachable;
		}

		/* An InetSocketAddress built from an InetAddress resolves nothing, but one of a null address is the
		   wildcard, which reaches the local host. A write from another thread is queued for the event loop, and a
		   closed channel is never writable. An address the local address cannot reach is dropped. */
		@Override
		public boolean send(InetAddress address, int port, byte[] bytes) {
			if (address == null || !reachable.test(address) || !channel.isWritable()) {
				return false;
			}

			try {
				channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(bytes),
					new InetSocketAddress(address, port)));
				return true;
			} catch (RuntimeException ex) {
				return false;
			}
		}

		@Override
		public InetSocketAddress localAddress() {
			return (InetSocketAddress) channel.localAddress();
		}

		@Override
		public void setReadable(boolean readable) {
			channel.config().setAutoRead(readable);
		}

		@Override
		public void close() {
			channel.close();
		}
	}

	static final class Receiver extends SimpleChannelInboundHandler<DatagramPacket> {
		private final Listener listener;

		Receiver(Listener listener) {
			this.listener = listener;
		}

		/* The content is copied only once the source was accepted, so a stranger cannot make the exit allocate */
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
			if (listener.accepts(packet.sender())) {
				listener.received(packet.sender(), ByteBufUtil.getBytes(packet.content()));
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			listener.closed();
			ctx.fireChannelInactive();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			/* A failed read, or a listener that throws, costs one datagram and not the socket */
		}
	}
}
