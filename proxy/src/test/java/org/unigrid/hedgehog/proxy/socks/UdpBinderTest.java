/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class UdpBinderTest {
	private static final int LARGEST_DATAGRAM = UdpAssociation.MAX_DATAGRAM_BYTES;
	private static final long WAIT_SECONDS = 5;

	@Example
	public void shouldBindNextToTheControlConnectionAndReadWholeDatagrams() throws Exception {
		final NioEventLoopGroup group = new NioEventLoopGroup(2);
		final InetAddress loopback = InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 });
		final BlockingQueue<byte[]> received = new LinkedBlockingQueue<>();

		try (ServerSocket server = new ServerSocket(0, 1, loopback); DatagramSocket client = new DatagramSocket()) {
			final Channel control = new Bootstrap().group(group).channel(NioSocketChannel.class)
				.handler(new ChannelInboundHandlerAdapter()).connect(server.getLocalSocketAddress()).sync()
				.channel();
			final ChannelHandler reader = new SimpleChannelInboundHandler<DatagramPacket>() {
				@Override
				protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
					received.add(ByteBufUtil.getBytes(packet.content()));
				}
			};
			final Channel socket = UdpBinder.NIO.bind(control, reader).sync().channel();
			final InetSocketAddress bound = (InetSocketAddress) socket.localAddress();
			final RecvByteBufAllocator buffers = socket.config().getRecvByteBufAllocator();
			final byte[] datagram = new byte[LARGEST_DATAGRAM];

			datagram[LARGEST_DATAGRAM - 1] = 7;
			client.send(new java.net.DatagramPacket(datagram, datagram.length, bound));

			assertThat(bound.getAddress(), equalTo(loopback));
			assertThat(socket.eventLoop(), sameInstance(control.eventLoop()));
			assertThat(buffers, instanceOf(FixedRecvByteBufAllocator.class));
			assertThat(buffers.newHandle().guess(), is(UdpAssociation.MAX_PACKET_BYTES));
			assertThat(received.poll(WAIT_SECONDS, TimeUnit.SECONDS), equalTo(datagram));
			assertThat(received.isEmpty(), is(true));
			socket.close().sync();
			control.close().sync();
		} finally {
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
		}
	}
}
