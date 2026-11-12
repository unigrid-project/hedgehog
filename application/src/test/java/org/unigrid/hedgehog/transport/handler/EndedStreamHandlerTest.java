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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.incubator.codec.quic.DefaultQuicStreamFrame;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.PreviewFrames;
import org.unigrid.hedgehog.model.network.QuicDialedPeer;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;

/* The main stream of a connection that a node run without --preview dialed, over QUIC to a peer that answers nothing
   and ends the stream when an example says so. The end of a stream is all that a peer says when it is done with it:
   the stream of the node stays open at its own end, for what the node writes and no one reads. */
public class EndedStreamHandlerTest extends BaseDefaultNodeTest {
	private static final Duration AT_ONCE = Duration.ofSeconds(1);
	private static final Duration WAIT = Duration.ofSeconds(20);
	private static final String TRANSPORT = "org.unigrid.hedgehog.transport";
	private static final String CLOSED = "Closed the connection to ";

	private final Logger logger = (Logger) LoggerFactory.getLogger(TRANSPORT);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	@BeforeTry
	public void listen() {
		appender.list.clear();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterTry
	public void stopListening() {
		logger.detachAppender(appender);
		appender.stop();
	}

	private static P2PClient dial(final QuicDialedPeer peer) throws Exception {
		return new P2PClient(peer.address().getHostString(), peer.address().getPort());
	}

	/* The end of the main stream at the peer, which is there once the hello of the node arrived */
	private static QuicStreamChannel mainStreamAt(final QuicDialedPeer peer) {
		await().atMost(WAIT).until(() -> !peer.streams().isEmpty());
		return peer.streams().get(0);
	}

	private static void awaitClosed(final P2PClient client) {
		await("the node to close the stream that its peer ended, and the connection").atMost(AT_ONCE)
			.until(() -> !client.getChannel().isActive() && !client.getChannel().parent().isActive());
	}

	/* What the transport told of the connections to the peer that it closed */
	private List<String> closesOfTheConnectionTo(final QuicDialedPeer peer) {
		synchronized (appender) {
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
				.filter(line -> line.startsWith(CLOSED) && line.contains(":" + peer.address().getPort() + ","))
				.toList();
		}
	}

	/* Whether the stream and its connection were open as each ping reached the stage that answers it */
	private static List<Boolean> openAsEachPingArrivesOn(final QuicStreamChannel stream) {
		final List<Boolean> open = new CopyOnWriteArrayList<>();
		final ChannelPipeline pipeline = stream.pipeline();

		pipeline.addBefore(pipeline.context(PingChannelHandler.class).name(), null, new ChannelInboundHandlerAdapter() {
			@Override
			public void channelRead(final ChannelHandlerContext ctx, final Object message) {
				if (message instanceof Ping) {
					open.add(stream.isActive() && stream.parent().isActive());
				}

				ctx.fireChannelRead(message);
			}
		});

		return open;
	}

	/* The peer closes its end of the stream alone, as a node does with a stream on which a frame does not come, and
	   leaves the connection as it is: the node would hold the peer as connected, and write to it, until its own pings
	   went unanswered, a minute or more later */
	@Example
	public void closesTheConnectionThatItDialedOnceThePeerEndsTheMainStream() throws Exception {
		try (QuicDialedPeer peer = QuicDialedPeer.offering(Network.getProtocols())) {
			final P2PClient client = dial(peer);

			try {
				mainStreamAt(peer).close();
				awaitClosed(client);
				assertThat(closesOfTheConnectionTo(peer), hasSize(1));
			} finally {
				client.closeDirty();
			}
		}
	}

	/* What the peer sent before the end is the node's to read: a frame that arrives together with the end of the
	   stream reaches the stages of the stream while it is open, and the connection is closed behind it */
	@Example
	public void handsOnTheFrameThatArrivesWithTheEndOfTheStreamBeforeItClosesTheConnection() throws Exception {
		try (QuicDialedPeer peer = QuicDialedPeer.offering(Network.getProtocols())) {
			final P2PClient client = dial(peer);

			try {
				final List<Boolean> open = openAsEachPingArrivesOn(client.getChannel());

				mainStreamAt(peer).writeAndFlush(new DefaultQuicStreamFrame(PreviewFrames.ping(), true));
				awaitClosed(client);
				assertThat(open, contains(true));
			} finally {
				client.closeDirty();
			}
		}
	}

	/* The topology closes the connection of a node once more as it drops it, and a node that stops closes every
	   connection it holds: neither finds anything left to close, and the loop of the connection ends */
	@Example
	public void closesAConnectionWhoseStreamThePeerEndedOnce() throws Exception {
		try (QuicDialedPeer peer = QuicDialedPeer.offering(Network.getProtocols())) {
			final P2PClient client = dial(peer);

			try {
				mainStreamAt(peer).close();
				awaitClosed(client);
			} finally {
				client.closeDirty();
			}

			client.close();
			await().atMost(WAIT).until(() -> client.getGroup().orElseThrow().isTerminated());
			assertThat(closesOfTheConnectionTo(peer), hasSize(1));
		}
	}

	/* A connection that the node closes of its own accord is none that its peer ended */
	@Example
	public void leavesAConnectionThatTheNodeEndsToTheCloseOfTheNode() throws Exception {
		try (QuicDialedPeer peer = QuicDialedPeer.offering(Network.getProtocols())) {
			final P2PClient client = dial(peer);

			mainStreamAt(peer);
			client.close();

			assertThat(client.getChannel().isActive(), is(false));
			assertThat(client.getChannel().parent().isActive(), is(false));
			assertThat(client.getGroup().orElseThrow().isTerminated(), is(true));
			assertThat(closesOfTheConnectionTo(peer), empty());
		}
	}
}
