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

package org.unigrid.hedgehog.transport.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicServerCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* The count of what left a stream, on a QUIC stream between two sockets of this machine: its peer lets it send no
   more than a window that the peer widens as it reads, so what leaves the stream is what the peer read and the
   window beyond that. */
public class PeerActivityHandlerTest {
	private static final String PROTOCOL = "hedgehog/test";
	private static final int WINDOW_BYTES = 16 * PeerActivityHandler.PIECE_BYTES;
	private static final int FRAME_BYTES = 256 * PeerActivityHandler.PIECE_BYTES + 1;
	private static final Duration WAIT = Duration.ofSeconds(10);
	private static final Duration STILL = Duration.ofSeconds(1);

	/* The end of a stream at the peer, which reads nothing of it before it is told to */
	private static ChannelHandler unread(CompletableFuture<QuicStreamChannel> opened) {
		return new ChannelInitializer<QuicStreamChannel>() {
			@Override
			protected void initChannel(QuicStreamChannel stream) {
				stream.config().setAutoRead(false);
				stream.pipeline().addLast(new ChannelInboundHandlerAdapter() {
					@Override public void channelRead(ChannelHandlerContext ctx, Object message) {
						ReferenceCountUtil.release(message);
					}
				});
				opened.complete(stream);
			}
		};
	}

	private static Channel bind(EventLoopGroup group, ChannelHandler codec) throws InterruptedException {
		return new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec)
			.bind(InetAddress.getLoopbackAddress(), 0).sync().channel();
	}

	private static ChannelHandler peer(CompletableFuture<QuicStreamChannel> opened) throws Exception {
		final SelfSignedCertificate certificate = new SelfSignedCertificate();

		try {
			return new QuicServerCodecBuilder()
				.sslContext(QuicSslContextBuilder.forServer(certificate.privateKey(), null, certificate.certificate())
					.applicationProtocols(PROTOCOL).build())
				.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				.initialMaxData(Network.MAX_WINDOW_SIZE)
				.initialMaxStreamDataBidirectionalRemote(WINDOW_BYTES)
				.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
				.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES)
				.handler(new ChannelInboundHandlerAdapter()).streamHandler(unread(opened)).build();
		} finally {
			certificate.delete();
		}
	}

	private static ChannelHandler node() {
		return new QuicClientCodecBuilder()
			.sslContext(QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
				.applicationProtocols(PROTOCOL).build())
			.initialMaxData(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
			.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
			.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES).build();
	}

	private static long taken(Channel stream) {
		return Objects.requireNonNullElse(stream.attr(Ping.TAKEN_BYTES_KEY).get(), 0L);
	}

	@Example
	public void countsWhatLeftAStreamOnlyAsFarAsItsPeerLetsItSend() throws Exception {
		final EventLoopGroup group = new NioEventLoopGroup(1);
		final CompletableFuture<QuicStreamChannel> opened = new CompletableFuture<>();

		try {
			final Channel peer = bind(group, peer(opened));
			final QuicChannel connection = QuicChannel.newBootstrap(bind(group, node()))
				.streamHandler(new ChannelInboundHandlerAdapter()).remoteAddress(peer.localAddress()).connect()
				.get(WAIT.toSeconds(), TimeUnit.SECONDS);
			final QuicStreamChannel stream = connection.createStream(QuicStreamType.BIDIRECTIONAL,
				new PeerActivityHandler()).sync().getNow();

			stream.writeAndFlush(Unpooled.directBuffer(FRAME_BYTES).writeZero(FRAME_BYTES));

			/* The window is sixteen pieces, and the peer reads nothing: no more leaves, however long it is waited */
			await().atMost(WAIT).until(() -> taken(stream) >= WINDOW_BYTES);
			await().pollDelay(STILL).atMost(WAIT).until(() -> true);
			assertThat(taken(stream), equalTo((long) WINDOW_BYTES));
			assertThat(taken(stream), lessThan((long) FRAME_BYTES));
			assertThat(stream.isWritable(), is(false));

			final QuicStreamChannel unread = opened.get(WAIT.toSeconds(), TimeUnit.SECONDS);

			unread.config().setAutoRead(true);
			unread.read();

			await().atMost(WAIT).until(() -> taken(stream) == FRAME_BYTES);
			await().atMost(WAIT).until(stream::isWritable);
		} finally {
			group.shutdownGracefully(0, WAIT.toSeconds(), TimeUnit.SECONDS).sync();
		}
	}
}
