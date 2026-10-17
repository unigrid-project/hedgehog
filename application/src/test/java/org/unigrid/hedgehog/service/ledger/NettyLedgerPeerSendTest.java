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

package org.unigrid.hedgehog.service.ledger;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.remoteAt;
import org.unigrid.hedgehog.ledger.Ballot;
import org.unigrid.hedgehog.ledger.Decision;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.WireCodec;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;

/* A stream takes only so much at a time, and a block is more than that: what is sent while it takes no more waits,
   and the votes go ahead of the rest when it takes more again */
public class NettyLedgerPeerSendTest {
	private static final String ADDRESS = "10.0.0.1";
	private static final int PORT = 52883;

	private static NettyLedgerNetwork network() {
		return new NettyLedgerNetwork(Set::of, new PendingRequests(), c -> false, c -> true);
	}

	private static NettyLedgerPeer peerOf(final Channel channel) {
		return network().peerOf(channel, remoteAt(ADDRESS, PORT));
	}

	private static List<String> told(final DebugLog log) {
		return log.events().stream().map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
	}

	private static void takeNoMore(final EmbeddedChannel channel) {
		channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
	}

	private static void takeMore(final EmbeddedChannel channel) {
		channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
		channel.runPendingTasks();
	}

	private static Decision block(final int seed) {
		return new Decision(LedgerWireFixtures.block(new Random(seed), 1 + seed));
	}

	private static Ballot vote(final int seed) {
		final Random random = new Random(seed);

		return LedgerWireFixtures.ballot(random, Ballot.Type.PREVOTE, 1 + seed, 0,
			LedgerWireFixtures.random(random, Digests.HASH_SIZE));
	}

	private static LedgerConsensus packet(final Message message) {
		return LedgerConsensus.builder().message(message).build();
	}

	@Example
	public void writesAVoteBeforeABlockThatWaitedWhenTheStreamTakesMoreAgain() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = peerOf(channel);

		takeNoMore(channel);
		peer.send(block(1));
		peer.send(vote(2));

		assertThat(channel.outboundMessages().isEmpty(), is(true));

		takeMore(channel);

		assertThat(channel.outboundMessages(), contains(packet(vote(2)), packet(block(1))));
	}

	@Example
	public void keepsTheOrderOfEachLane() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = peerOf(channel);

		takeNoMore(channel);
		peer.send(block(1));
		peer.send(vote(1));
		peer.send(block(2));
		peer.send(vote(2));
		peer.send(block(3));
		peer.send(vote(3));
		takeMore(channel);

		assertThat(channel.outboundMessages(), contains(packet(vote(1)), packet(vote(2)), packet(vote(3)),
			packet(block(1)), packet(block(2)), packet(block(3))));
	}

	@Example
	public void dropsTheNewMessageOfAFullLaneOfVotesAndCountsItAndKeepsWhatWaits() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final NettyLedgerNetwork network = network();
		final LedgerPeer peer = network.peerOf(channel, remoteAt(ADDRESS, PORT));
		final int fit = OutboundQueue.Lane.VOTES.limit() / WireCodec.HEARTBEAT_SIZE;
		final List<Heartbeat> heartbeats = IntStream.range(0, fit + 3).mapToObj(LedgerWireFixtures::heartbeat).toList();

		takeNoMore(channel);

		try (DebugLog log = new DebugLog(NettyLedgerPeer.class)) {
			heartbeats.forEach(peer::send);

			assertThat(told(log), contains(Collections.nCopies(3,
				"DEBUG Dropped what was sent to " + ADDRESS + ": too much waits in its VOTES lane").toArray()));
		}

		assertThat(network.droppedOutbound(), is(3L));
		takeMore(channel);

		assertThat(List.copyOf(channel.outboundMessages()), equalTo(heartbeats.subList(0, fit).stream()
			.map(heartbeat -> (Object) LedgerHeartbeat.builder().heartbeat(heartbeat).build()).toList()));
	}

	@Example
	public void holdsTwoOfTheLargestBlocksAndDropsTheNextOnesButNotTheVotes() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final NettyLedgerNetwork network = network();
		final LedgerPeer peer = network.peerOf(channel, remoteAt(ADDRESS, PORT));
		final Decision largest = new Decision(LedgerWireFixtures.largestBlock(new Random(1)));

		takeNoMore(channel);
		peer.send(largest);
		peer.send(largest);

		assertThat(network.droppedOutbound(), is(0L));

		IntStream.range(0, 5).forEach(n -> peer.send(largest));
		peer.send(vote(1));

		assertThat(network.droppedOutbound() > 0, is(true));
		takeMore(channel);

		final List<Object> written = List.copyOf(channel.outboundMessages());

		assertThat(written.get(0), equalTo(packet(vote(1))));
		assertThat(written.subList(1, written.size()), equalTo(Collections.nCopies(written.size() - 1, packet(largest))));
		assertThat(written.size() >= 3, is(true));
		assertThat(written.size() - 1L + network.droppedOutbound(), is(7L));
	}

	@Example
	public void dropsEverythingSentToAClosedStreamAndWhatWaitedForAStreamThatClosed() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final NettyLedgerPeer peer = peerOf(channel);

		takeNoMore(channel);
		peer.send(block(1));
		peer.send(vote(1));

		assertThat(peer.waiting(), is(2));

		channel.close();

		assertThat(peer.waiting(), is(0));

		try (DebugLog log = new DebugLog(NettyLedgerPeer.class)) {
			peer.send(vote(2));
			peer.send(block(2));

			assertThat(told(log), contains(Collections.nCopies(2,
				"DEBUG Dropped what was sent to " + ADDRESS + ": its stream is closed").toArray()));
		}

		assertThat(peer.waiting(), is(0));
		assertThat(channel.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void writesAtOnceWhatTheStreamTakes() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final LedgerPeer peer = peerOf(channel);

		peer.send(block(1));
		peer.send(vote(1));

		assertThat(channel.outboundMessages(), contains(packet(block(1)), packet(vote(1))));
	}

	@Example
	public void writesWhatWaitsAfterEachCompletedWriteEvenIfNoWritabilityChangeIsHeard() {
		final HoldTheFirstWrite hold = new HoldTheFirstWrite();
		final EmbeddedChannel channel = new EmbeddedChannel(new SwallowWritabilityChanges(), hold);
		final LedgerPeer peer = peerOf(channel);

		peer.send(vote(1));
		peer.send(vote(2));
		peer.send(block(3));

		assertThat(channel.outboundMessages(), contains(packet(vote(1))));

		takeMore(channel);

		assertThat(channel.outboundMessages(), contains(packet(vote(1))));

		hold.complete();
		channel.runPendingTasks();

		assertThat(channel.outboundMessages(), contains(packet(vote(1)), packet(vote(2)), packet(block(3))));
	}

	@Example
	public void writesWhatEveryThreadSendsOnTheEventLoopOfTheStreamExactlyOnce() throws Exception {
		final DefaultEventLoopGroup group = new DefaultEventLoopGroup(2);
		final ConcurrentLinkedQueue<Object> received = new ConcurrentLinkedQueue<>();
		final LocalAddress address = new LocalAddress("peer-send-test");

		try {
			new ServerBootstrap().group(group).channel(LocalServerChannel.class)
				.childHandler(new ChannelInitializer<LocalChannel>() {
					@Override
					protected void initChannel(final LocalChannel channel) {
						channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
							@Override
							public void channelRead(final ChannelHandlerContext ctx, final Object message) {
								received.add(message);
							}
						});
					}
				}).bind(address).sync();

			final Channel stream = new Bootstrap().group(group).channel(LocalChannel.class)
				.handler(new ChannelInboundHandlerAdapter()).connect(address).sync().channel();
			final LedgerPeer peer = peerOf(stream);
			final int threads = 8;
			final int each = 50;
			final List<Thread> senders = IntStream.range(0, threads).mapToObj(thread -> new Thread(() ->
				IntStream.range(0, each).forEach(n -> peer.send(LedgerWireFixtures.heartbeat(thread * each + n)))))
				.toList();

			senders.forEach(Thread::start);
			for (Thread sender : senders) {
				sender.join();
			}

			await().until(() -> received.size() >= threads * each);

			assertThat(received.stream().filter(LedgerHeartbeat.class::isInstance).count(),
				is((long) threads * each));
			assertThat(received.stream().distinct().count(), is((long) threads * each));
		} finally {
			group.shutdownGracefully().sync();
		}
	}

	/* A write that is accepted and that completes only when the test says, as one does that a stream holds back */
	private static final class HoldTheFirstWrite extends ChannelOutboundHandlerAdapter {
		private ChannelPromise held;

		@Override
		public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise)
			throws Exception {

			if (held == null) {
				held = promise;
				ctx.channel().unsafe().outboundBuffer().setUserDefinedWritability(1, false);
				ctx.write(message, ctx.voidPromise());
			} else {
				ctx.write(message, promise);
			}
		}

		private void complete() {
			held.setSuccess();
		}
	}

	private static final class SwallowWritabilityChanges extends ChannelInboundHandlerAdapter {
		@Override
		public void channelWritabilityChanged(final ChannelHandlerContext ctx) {
			/* Not passed on, so that the handlers after this one hear nothing of it */
		}
	}
}
