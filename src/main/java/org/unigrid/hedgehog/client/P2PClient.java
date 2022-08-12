/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.concurrent.ExecutionException;
import lombok.Cleanup;
import org.unigrid.hedgehog.model.network.handler.BasicChannelHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.security.NoSuchAlgorithmException;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.handler.RegisterQuicHandler;
import org.unigrid.hedgehog.model.network.codec.PublishSporkDecoder;
import org.unigrid.hedgehog.model.network.codec.PublishSporkEncoder;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;

public class P2PClient {
	public static void connectAndSend(String hostname, int port)
		throws ExecutionException, InterruptedException, CertificateException, NoSuchAlgorithmException {

		@Cleanup("shutdownGracefully")
		final NioEventLoopGroup group = new NioEventLoopGroup(1);

		//final SelfSignedCertificate certificate = new SelfSignedCertificate();
		final QuicSslContext context = QuicSslContextBuilder.forClient()
			.trustManager(InsecureTrustManagerFactory.INSTANCE)
			.applicationProtocols(Network.PROTOCOLS)
			.build();

		final ChannelHandler codec = new QuicClientCodecBuilder()
			.initialMaxData(Network.MAX_DATA_SIZE)
			.sslContext(context)
			.build();

		final Bootstrap bootstrap = new Bootstrap();
		final Channel channel = bootstrap.group(group)
			.channel(NioDatagramChannel.class)
			.handler(codec)
			.bind(0).sync().channel();

		/* TODO: What is this? .streamHandler(*/

		final QuicChannel quicChannel = QuicChannel.newBootstrap(channel)
			.streamHandler(new RegisterQuicHandler(
				new PublishSporkDecoder(),
				new PublishSporkEncoder(),
				new BasicChannelHandler()))
			.remoteAddress(new InetSocketAddress(hostname, port))
			.connect().get();

		for (int i = 0; i < 20; i++) {
			final QuicStreamChannel streamChannel = getStreamChannel(quicChannel);

			streamChannel.pipeline().addLast(new PublishSporkDecoder(),
				new PublishSporkEncoder());

			System.out.println("send message:" + i + "; " + streamChannel);
			System.out.println(quicChannel);

			streamChannel.writeAndFlush(new PublishSpork()).sync();

			streamChannel.writeAndFlush(
				Unpooled.copiedBuffer("GET /\r\n", CharsetUtil.ISO_8859_1)).addListener(future -> {
					if (future.isSuccess()) {
						System.out.println("success!" + streamChannel);
					} else {
						System.out.println("fail!" + streamChannel);
					}
				}
			).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).sync();

			// TODO: Probably remove? Thread.sleep(300);
		}
	}

	private static QuicStreamChannel getStreamChannel(QuicChannel quicChannel) throws InterruptedException {
		return quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
			new ChannelInboundHandlerAdapter() {
				@Override
				public void channelRead(ChannelHandlerContext ctx, Object msg) {
					final ByteBuf byteBuf = (ByteBuf) msg;
					System.err.println(byteBuf.toString(CharsetUtil.US_ASCII));
					byteBuf.release();
				}

				@Override
				public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
					if (evt == ChannelInputShutdownReadComplete.INSTANCE) {
						System.out.println("ChannelInputShutdownReadComplete");
						// Close the connection once the remote peer did send the FIN for this
						// ((QuicChannel) ctx.channel().parent()).close(true, 0,
						//         ctx.alloc().directBuffer(16)
						//                 .writeBytes(new byte[]{'k', 't', 'h', 'x', 'b',
						//                             'y', 'e'}));
					}
				}
			}).sync().getNow();
	}
}
