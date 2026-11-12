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

package org.unigrid.hedgehog.model.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicClientCodecBuilder;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import io.netty.incubator.codec.quic.QuicStreamType;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import static org.awaitility.Awaitility.await;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.packet.Ping;

/* A QUIC client that dials a node that is already running and offers the protocols it is given, as a peer would. The
   streams it opens keep the frames the node writes to them, so that a test can tell what the node answered. */
public final class QuicPeer implements AutoCloseable {
	private static final int WAIT_SECONDS = 5;
	private static final int HEADER = 8;
	private static final int ANSWER_FLAG_AT = Long.BYTES;
	private static final Duration POLL = Duration.ofMillis(1);

	private final EventLoopGroup group = new NioEventLoopGroup(1);
	private final Channel socket;
	private final QuicChannel connection;

	/* A frame that arrived whole: the type from its header and the bytes that follow the header */
	public record Frame(Packet.Type type, byte[] body) {
		public boolean isAnswerToPing(final long nanoTime) {
			return type == Packet.Type.PING && body.length > ANSWER_FLAG_AT
				&& ByteBuffer.wrap(body).getLong() == nanoTime && (body[ANSWER_FLAG_AT] & 1) == 1;
		}
	}

	/* One stream of the connection. The node may close it at any time, as it does with a peer it has no use for. */
	public static final class Stream extends ChannelInboundHandlerAdapter {
		private final ByteArrayOutputStream received = new ByteArrayOutputStream();
		private QuicStreamChannel channel;

		@Override
		public void channelRead(final ChannelHandlerContext ctx, final Object message) {
			final ByteBuf bytes = (ByteBuf) message;

			try {
				synchronized (received) {
					received.writeBytes(ByteBufUtil.getBytes(bytes));
				}
			} finally {
				bytes.release();
			}
		}

		public long streamId() {
			return channel.streamId();
		}

		/* Whether the node has not closed its end, nor this end been closed */
		public boolean isOpen() {
			return channel.isActive() && !channel.isInputShutdown();
		}

		/* Writes a copy of the frame, so that the same frame can be written again. Nothing leaves before flush(). */
		public ChannelFuture send(final ByteBuf frame) {
			return channel.write(frame.retainedDuplicate());
		}

		public void flush() {
			channel.flush();
		}

		/* Sends a ping that is not any other, and gives the time it carries */
		public long ping() {
			final long nanoTime = System.nanoTime();

			send(PreviewFrames.frameOf(new PingEncoder(), Ping.builder().nanoTime(nanoTime).build()));
			flush();
			return nanoTime;
		}

		public boolean hasAnswerToPing(final long nanoTime) {
			return frames().stream().anyMatch(frame -> frame.isAnswerToPing(nanoTime));
		}

		/* Until the node answers the ping, or closes the stream, which says that it read everything before it */
		public void awaitAnswerOrClose(final long nanoTime) {
			await("an answer to the ping on stream " + channel.streamId()).pollInSameThread().pollDelay(Duration.ZERO)
				.pollInterval(POLL).atMost(WAIT_SECONDS, TimeUnit.SECONDS)
				.until(() -> hasAnswerToPing(nanoTime) || !isOpen());
		}

		/* Until the node answers the ping, which is not given by a stream that is closed */
		public void awaitAnswer(final long nanoTime) {
			await("an answer to the ping on stream " + channel.streamId()).pollInSameThread().pollDelay(Duration.ZERO)
				.pollInterval(POLL).atMost(WAIT_SECONDS, TimeUnit.SECONDS).until(() -> hasAnswerToPing(nanoTime));
		}

		public List<Frame> frames() {
			final ByteBuffer bytes;

			synchronized (received) {
				bytes = ByteBuffer.wrap(received.toByteArray());
			}

			final List<Frame> frames = new ArrayList<>();

			while (bytes.remaining() >= HEADER) {
				final int start = bytes.position();
				final Packet.Type type = Packet.Type.get(bytes.getShort(start + Short.BYTES));
				final int size = bytes.getInt(start + 2 * Short.BYTES);

				if (bytes.remaining() < HEADER + size) {
					break;
				}

				final byte[] body = new byte[size];

				bytes.position(start + HEADER);
				bytes.get(body);
				frames.add(new Frame(type, body));
			}

			return frames;
		}

		@SneakyThrows
		public void close() {
			channel.close().sync();
		}
	}

	/* Throws what the handshake fails with, as it does when the node offers none of the protocols given */
	@SneakyThrows
	private QuicPeer(final String host, final int port, final String... protocols) {
		try {
			socket = new Bootstrap().group(group).channel(NioDatagramChannel.class)
				.handler(new QuicClientCodecBuilder()
					.sslContext(QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
						.applicationProtocols(protocols).build())
					.initialMaxData(Network.MAX_WINDOW_SIZE)
					.initialMaxStreamDataBidirectionalLocal(Network.MAX_WINDOW_SIZE)
					.initialMaxStreamDataBidirectionalRemote(Network.MAX_WINDOW_SIZE)
					.initialMaxStreamsBidirectional(Network.MAX_STREAMS)
					.maxIdleTimeout(Network.IDLE_TIME_MINUTES, TimeUnit.MINUTES).build())
				.bind(0).sync().channel();

			connection = QuicChannel.newBootstrap(socket).streamHandler(new ChannelInboundHandlerAdapter())
				.remoteAddress(new InetSocketAddress(host, port)).connect().get(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception ex) {
			group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS);
			throw ex;
		}
	}

	public static QuicPeer dial(final String host, final int port, final String... protocols) {
		return new QuicPeer(host, port, protocols);
	}

	public QuicChannel connection() {
		return connection;
	}

	public String protocol() {
		return connection.sslEngine().getApplicationProtocol();
	}

	/* A connection takes Network.MAX_STREAMS streams from its peer. A stream that was closed gave nothing back within
	   seconds, in a loopback between two plain servers too, so a test that needs more streams dials again. */
	@SneakyThrows
	public Stream open() {
		final Stream stream = new Stream();

		stream.channel = connection.createStream(QuicStreamType.BIDIRECTIONAL, stream).sync().getNow();
		return stream;
	}

	@Override
	public void close() throws InterruptedException {
		connection.close().sync();
		socket.close().sync();
		group.shutdownGracefully(0, WAIT_SECONDS, TimeUnit.SECONDS).sync();
	}
}
