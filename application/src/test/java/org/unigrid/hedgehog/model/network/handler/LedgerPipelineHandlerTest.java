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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;
import org.objenesis.ObjenesisStd;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.codec.FrameDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockBatchDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockBatchEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockRequestDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerBlockRequestEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerConsensusDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerConsensusEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerHeartbeatDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerHeartbeatEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerStatusDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerStatusEncoder;
import org.unigrid.hedgehog.model.network.codec.LedgerTransactionDecoder;
import org.unigrid.hedgehog.model.network.codec.LedgerTransactionEncoder;
import org.unigrid.hedgehog.model.network.codec.PingEncoder;
import org.unigrid.hedgehog.model.network.initializer.LedgerPipeline;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.model.network.schedule.LedgerStatusSchedule;
import org.unigrid.hedgehog.service.ledger.DebugLog;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;
import org.unigrid.hedgehog.service.ledger.LedgerWireFixtures;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.bodyOf;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.encoded;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.sentBy;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.unreadableBatch;

@WeldSetup(value = { LedgerGateway.class, PendingRequests.class, Topology.class, ChannelMap.class,
	ProtectedInterceptor.class }, scan = false)
public class LedgerPipelineHandlerTest extends BaseMockedWeldTest {
	private static final int PORT = 52883;
	private static final int LOOPBACK_NETWORK = 127;
	private static final String PEER = "10.1.2.3";
	private static final String OTHER = "10.4.5.6";

	private record Call(String method, LedgerPeer from, Object argument) {
	}

	/* Everything that reaches the end of the pipeline, which only what no handler took gets to */
	private static final class Remainder extends ChannelInboundHandlerAdapter {
		private final List<Object> read = new ArrayList<>();

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object message) {
			read.add(message);
		}
	}

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private LedgerGateway gateway;
	@Inject private PendingRequests pending;

	private final List<Call> calls = new ArrayList<>();
	private final List<Integer> sizes = new ArrayList<>();
	private CompletableFuture<BlockBatch> answer;
	private RuntimeException failure;

	@BeforeTry
	public void attachAStubDriver() {
		calls.clear();
		sizes.clear();
		failure = null;
		answer = CompletableFuture.completedFuture(LedgerWireFixtures.emptyBatch());
		recordTheCallsOfADriver();
		gateway.attach(aDriver());
	}

	private static LedgerDriver aDriver() {
		return new ObjenesisStd().newInstance(LedgerDriver.class);
	}

	private void record(final String method, final LedgerPeer from, final Object argument) {
		if (failure != null) {
			throw failure;
		}

		calls.add(new Call(method, from, argument));
	}

	private void record(final String method, final LedgerPeer from, final Object argument, final int bytes) {
		record(method, from, argument);
		sizes.add(bytes);
	}

	private void recordTheCallsOfADriver() {
		new MockUp<LedgerDriver>() {
			@Mock public void onTransaction(LedgerPeer from, Transaction transaction, int bytes) {
				record("onTransaction", from, transaction, bytes);
			}

			@Mock public void onMessage(LedgerPeer from, Message message, int bytes) {
				record("onMessage", from, message, bytes);
			}

			@Mock public void onHeartbeat(LedgerPeer from, Heartbeat heartbeat, int bytes) {
				record("onHeartbeat", from, heartbeat, bytes);
			}

			@Mock public void onStatus(LedgerPeer from, Status status, int bytes) {
				record("onStatus", from, status, bytes);
			}

			@Mock public CompletableFuture<BlockBatch> onBlockRequest(LedgerPeer from, BlockRequest request,
				int bytes) {

				record("onBlockRequest", from, request, bytes);
				return answer;
			}

			@Mock public void onPeerConnected(LedgerPeer from) {
				record("onPeerConnected", from, null);
			}
		};
	}

	@Provide
	Arbitrary<String> addresses() {
		return Arbitraries.integers().between(1, 254).list().ofSize(4)
			.filter(parts -> parts.get(0) != LOOPBACK_NETWORK)
			.map(parts -> String.join(".", parts.stream().map(String::valueOf).toList()));
	}

	/* A connection that settled on the ledger protocol, as the ones of nodes that speak it do */
	private static EmbeddedChannel connectionFrom(final String address) {
		final EmbeddedChannel connection = connectionWithoutTheLedgerFrom(address);

		connection.attr(LedgerProtocol.NEGOTIATED_KEY).set(true);
		return connection;
	}

	private static EmbeddedChannel connectionWithoutTheLedgerFrom(final String address) {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(ConnectionHandler.SOCKET_ADDRESS_KEY).set(new InetSocketAddress(address, PORT));
		return connection;
	}

	private static EmbeddedChannel streamOf(final Channel connection, final Remainder remainder) {
		final List<ChannelHandler> handlers = new ArrayList<>();

		handlers.add(new FrameDecoder());
		handlers.addAll(LedgerPipeline.handlers());
		handlers.add(remainder);
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false,
			handlers.toArray(new ChannelHandler[0]));
	}

	private static EmbeddedChannel streamFrom(final String address) {
		return streamOf(connectionFrom(address), new Remainder());
	}

	private static final int KINDS = 5;

	/* The frame of each kind of packet that a peer sends unasked: the five that the driver is handed */
	private static ByteBuf frameOfKind(final int kind, final int seed) {
		return encoded(switch (kind) {
			case 0 -> LedgerTransaction.builder().transaction(LedgerWireFixtures.transaction(seed)).build();
			case 1 -> LedgerConsensus.builder().message(LedgerWireFixtures.message(seed)).build();
			case 2 -> LedgerHeartbeat.builder().heartbeat(LedgerWireFixtures.heartbeat(seed)).build();
			case 3 -> LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build();
			default -> LedgerBlockRequest.builder().requestId(seed).request(LedgerWireFixtures.request(seed))
				.build();
		});
	}

	private void sendAllKinds(final EmbeddedChannel channel, final int seed) {
		IntStream.range(0, KINDS).forEach(kind -> channel.writeInbound(frameOfKind(kind, seed)));
	}

	@Property(tries = 10)
	public void deliversEachPacketToTheMatchingMethodWithAPeerOfTheRemoteAddress(
		@ForAll("addresses") String address, @ForAll @IntRange(min = 0, max = 100) int seed) {

		final EmbeddedChannel channel = streamFrom(address);

		sendAllKinds(channel, seed);

		assertThat(calls.stream().map(Call::method).toList(), contains("onTransaction", "onMessage", "onHeartbeat",
			"onStatus", "onBlockRequest"));
		assertThat(calls.stream().map(Call::argument).toList(), contains(LedgerWireFixtures.transaction(seed),
			LedgerWireFixtures.message(seed), LedgerWireFixtures.heartbeat(seed),
			LedgerWireFixtures.status(seed), LedgerWireFixtures.request(seed)));
		assertThat(calls.stream().map(call -> call.from().id()).distinct().toList(), contains(address));
		assertThat(calls.stream().map(Call::from).distinct().count(), is(1L));
		assertThat(calls.get(0).from(), sameInstance(gateway.peerOf(channel, () -> RemoteAddress.of(channel))));
	}

	@Property(tries = 10)
	public void passesTheSizeOfTheFrameOfEachPacketToTheDriver(@ForAll("addresses") String address,
		@ForAll @IntRange(min = 0, max = 100) int seed) {

		final EmbeddedChannel channel = streamFrom(address);
		final List<Integer> bodies = IntStream.range(0, KINDS).mapToObj(kind -> {
			final ByteBuf frame = frameOfKind(kind, seed);
			final int body = frame.readableBytes() - LedgerWireFixtures.FRAME_HEADER_SIZE;

			frame.release();
			return body;
		}).toList();

		sendAllKinds(channel, seed);

		assertThat(sizes, equalTo(bodies));
	}

	@Example
	public void namesTwoChannelsOfOneAddressAlike() {
		final EmbeddedChannel first = streamFrom(PEER);
		final EmbeddedChannel second = streamFrom(PEER);

		sendAllKinds(first, 1);
		sendAllKinds(second, 1);

		assertThat(calls.stream().map(call -> call.from().id()).distinct().toList(), contains(PEER));
		assertThat(calls.stream().map(Call::from).distinct().count(), is(2L));
	}

	@Example
	public void namesTwoLocalStreamsApartByTheSocketsOfTheirConnections() {
		final EmbeddedChannel first = streamOf(localConnection(PORT + 1), new Remainder());
		final EmbeddedChannel second = streamOf(localConnection(PORT + 2), new Remainder());

		sendAllKinds(first, 1);
		sendAllKinds(second, 1);

		assertThat(calls.stream().map(call -> call.from().id()).distinct().toList(),
			containsInAnyOrder("127.0.0.1:" + (PORT + 1), "127.0.0.1:" + (PORT + 2)));
	}

	private static EmbeddedChannel localConnection(final int port) {
		final EmbeddedChannel connection = connectionFrom("127.0.0.1");

		connection.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).set(new InetSocketAddress("127.0.0.1", port));
		return connection;
	}

	@Property(tries = 5)
	public void passesAPacketOfAnotherTypeOnUntouched(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final Remainder remainder = new Remainder();
		final EmbeddedChannel channel = streamOf(connectionFrom(PEER), remainder);
		final EmbeddedChannel encoder = new EmbeddedChannel(new PingEncoder());

		encoder.writeOutbound(Ping.builder().nanoTime(seed).build());

		final ByteBuf frame = encoder.readOutbound();
		final byte[] body = bodyOf(frame);

		channel.writeInbound(frame);

		assertThat(remainder.read.size(), is(1));
		assertThat(remainder.read.get(0), instanceOf(ByteBuf.class));
		assertThat(ByteBufUtil.getBytes((ByteBuf) remainder.read.get(0)), equalTo(body));
		assertThat(calls.isEmpty(), is(true));
	}

	@Property(tries = 5)
	public void dropsEveryPacketWhileNoDriverIsAttached(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final EmbeddedChannel channel = streamFrom(PEER);

		gateway.detach();
		sendAllKinds(channel, seed);
		channel.writeInbound(encoded(LedgerBlockBatch.builder().requestId(seed)
			.batch(LedgerWireFixtures.emptyBatch()).build()));
		channel.checkException();

		assertThat(calls.isEmpty(), is(true));
		assertThat(channel.isOpen(), is(true));
		assertThat(channel.outboundMessages().isEmpty(), is(true));
	}

	@Property(tries = 5)
	public void answersABlockRequestOnTheChannelItCameOn(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final EmbeddedChannel connection = connectionFrom(PEER);
		final EmbeddedChannel channel = streamOf(connection, new Remainder());
		final EmbeddedChannel bystander = streamFrom(OTHER);

		bystander.writeInbound(encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build()));
		channel.writeInbound(encoded(LedgerBlockRequest.builder().requestId(seed)
			.request(LedgerWireFixtures.request(seed)).build()));

		final LedgerBlockBatch reply = (LedgerBlockBatch) sentBy(channel);

		assertThat(reply.getRequestId(), equalTo((long) seed));
		assertThat(reply.getBatch(), equalTo(LedgerWireFixtures.emptyBatch()));
		assertThat(channel.outboundMessages().isEmpty(), is(true));
		assertThat(connection.outboundMessages().isEmpty(), is(true));
		assertThat(bystander.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void answersWhenTheDriverHasTheBlocksAndNotBefore() {
		final EmbeddedChannel channel = streamFrom(PEER);

		answer = new CompletableFuture<>();
		channel.writeInbound(encoded(LedgerBlockRequest.builder().requestId(7)
			.request(LedgerWireFixtures.request(7)).build()));
		assertThat(channel.outboundMessages().isEmpty(), is(true));

		answer.complete(LedgerWireFixtures.emptyBatch());
		assertThat(((LedgerBlockBatch) sentBy(channel)).getRequestId(), equalTo(7L));
	}

	@Example
	public void leavesARequestUnansweredWhileTheStreamIsBacklogged() {
		final EmbeddedChannel channel = streamFrom(PEER);
		final LedgerBlockRequest request = LedgerBlockRequest.builder().requestId(7)
			.request(LedgerWireFixtures.request(7)).build();

		channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);

		try (DebugLog log = new DebugLog(LedgerBlockRequestChannelHandler.class)) {
			channel.writeInbound(encoded(request));
			assertThat(log.events().stream().map(ILoggingEvent::getFormattedMessage).toList(),
				contains(startsWith("Left a block request of " + PEER)));
		}

		assertThat(channel.outboundMessages().isEmpty(), is(true));

		channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
		channel.writeInbound(encoded(request));
		assertThat(((LedgerBlockBatch) sentBy(channel)).getRequestId(), equalTo(7L));
	}

	@Property(tries = 10)
	public void closesTheStreamOfAFrameThatEndsEarlyOrLate(@ForAll boolean longer,
		@ForAll @IntRange(min = 1, max = 4) int change, @ForAll @IntRange(min = 0, max = 100) int seed) {

		final EmbeddedChannel channel = streamFrom(PEER);
		final EmbeddedChannel other = streamFrom(OTHER);
		final ByteBuf frame = encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build());

		channel.writeInbound(LedgerWireFixtures.resized(frame, longer ? change : -change));
		assertThat(channel.isOpen(), is(false));
		assertThat(calls.isEmpty(), is(true));

		other.writeInbound(frame);
		assertThat(calls.stream().map(Call::argument).toList(), contains(LedgerWireFixtures.status(seed)));
		assertThat(other.isOpen(), is(true));
	}

	@Example
	public void closesTheStreamWhenTheDriverFails() {
		final EmbeddedChannel channel = streamFrom(PEER);

		failure = new IllegalStateException("The driver is broken");
		channel.writeInbound(encoded(LedgerTransaction.builder().transaction(LedgerWireFixtures.transaction(1))
			.build()));

		assertThat(channel.isOpen(), is(false));
	}

	@Property(tries = 5)
	public void dropsWhatADriverThatTakesNoMoreWouldGetAndKeepsTheStreamOpen(
		@ForAll @IntRange(min = 0, max = 100) int seed) {

		final EmbeddedChannel channel = streamFrom(PEER);

		failure = new RejectedExecutionException("The driver takes no more");
		sendAllKinds(channel, seed);
		channel.checkException();

		assertThat(channel.isOpen(), is(true));
		assertThat(channel.outboundMessages().isEmpty(), is(true));
		assertThat(calls.isEmpty(), is(true));

		failure = null;
		channel.writeInbound(encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build()));
		assertThat(calls.stream().map(Call::argument).toList(), contains(LedgerWireFixtures.status(seed)));
	}

	@Property(tries = 5)
	public void keepsTheScheduleRunningWhateverTheDriverDoes(@ForAll boolean rejects) {
		final Consumer<Channel> announce = new LedgerStatusSchedule().getConsumer();

		failure = rejects ? new RejectedExecutionException("The driver takes no more")
			: new IllegalStateException("The driver is broken");
		announce.accept(streamFrom(PEER));
		announce.accept(streamFrom(OTHER));

		failure = null;
		announce.accept(streamFrom(PEER));
		assertThat(calls.stream().map(Call::method).toList(), contains("onPeerConnected"));
	}

	@Example
	public void doesNotAnswerARefusedRequestAndKeepsTheStreamOpen() {
		final EmbeddedChannel channel = streamFrom(PEER);

		answer = CompletableFuture.failedFuture(new RejectedExecutionException("The request is refused"));
		channel.writeInbound(encoded(LedgerBlockRequest.builder().requestId(7)
			.request(LedgerWireFixtures.request(7)).build()));
		channel.checkException();

		assertThat(channel.outboundMessages().isEmpty(), is(true));
		assertThat(channel.isOpen(), is(true));
	}

	@Property(tries = 5)
	public void completesTheRegisteredRequestWithTheBatch(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final EmbeddedChannel channel = streamFrom(PEER);
		final long requestId = pending.nextRequestId();
		final CompletableFuture<LedgerBlockBatch> waiting = pending.register(requestId, channel,
			LedgerBlockBatch.class);

		channel.writeInbound(encoded(LedgerBlockBatch.builder().requestId(requestId)
			.batch(LedgerWireFixtures.emptyBatch()).build()));

		assertThat(waiting.isDone(), is(true));
		assertThat(waiting.getNow(null).getBatch(), equalTo(LedgerWireFixtures.emptyBatch()));
		assertThat(calls.isEmpty(), is(true));
	}

	@Property(tries = 6)
	public void readsABatchOnlyForARequestOfThatStream(@ForAll @IntRange(min = 0, max = 2) int awaitedOn) {
		final EmbeddedChannel channel = streamFrom(PEER);
		final EmbeddedChannel other = streamFrom(OTHER);
		final long requestId = pending.nextRequestId();

		if (awaitedOn > 0) {
			pending.register(requestId, awaitedOn == 1 ? channel : other, LedgerBlockBatch.class);
		}

		channel.writeInbound(unreadableBatch(requestId));

		assertThat(channel.isOpen(), is(awaitedOn != 1));
		assertThat(calls.isEmpty(), is(true));
		pending.fail(requestId, new RejectedExecutionException("done with the request"));
	}

	@Property(tries = 5)
	public void tellsWhatItCannotDecodeInOneLineAtDebugLevel(@ForAll @IntRange(min = 0, max = 100) int seed) {
		final EmbeddedChannel channel = streamFrom(PEER);
		final ByteBuf frame = LedgerWireFixtures.resized(encoded(LedgerStatus.builder()
			.status(LedgerWireFixtures.status(seed)).build()), 1);

		try (DebugLog log = new DebugLog(AbstractInboundHandler.class)) {
			channel.writeInbound(frame);

			assertThat(log.events().stream().map(event -> event.getLevel() + " " + event.getFormattedMessage())
				.toList(), contains(startsWith("DEBUG Closed a stream: ")));
			assertThat(log.events().get(0).getFormattedMessage().contains("\n"), is(false));
		}

		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void tellsAFailureOfTheNodeInFullAtWarningLevel() {
		final EmbeddedChannel channel = streamFrom(PEER);

		failure = new IllegalStateException("The driver is broken");

		try (DebugLog log = new DebugLog(AbstractInboundHandler.class)) {
			channel.writeInbound(encoded(LedgerTransaction.builder()
				.transaction(LedgerWireFixtures.transaction(1)).build()));

			assertThat(log.events().stream().map(ILoggingEvent::getLevel).toList(), contains(Level.WARN));
			assertThat(log.events().get(0).getFormattedMessage().contains("IllegalStateException"), is(true));
		}

		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void ignoresABatchThatComesOnAnotherChannel() {
		final EmbeddedChannel channel = streamFrom(PEER);
		final EmbeddedChannel stranger = streamFrom(OTHER);
		final long requestId = pending.nextRequestId();
		final CompletableFuture<LedgerBlockBatch> waiting = pending.register(requestId, channel,
			LedgerBlockBatch.class);

		stranger.writeInbound(encoded(LedgerBlockBatch.builder().requestId(requestId)
			.batch(LedgerWireFixtures.emptyBatch()).build()));

		assertThat(waiting.isDone(), is(false));
		pending.fail(requestId, new RejectedExecutionException("done with the request"));
	}

	@Example
	public void completesARequestOfThePeerWithTheReplyOfItsChannel() {
		final EmbeddedChannel channel = streamFrom(PEER);
		final LedgerPeer peer = gateway.peerOf(channel, () -> RemoteAddress.of(channel));
		final CompletableFuture<BlockBatch> reply = peer.request(LedgerWireFixtures.request(3));
		final LedgerBlockRequest sent = (LedgerBlockRequest) sentBy(channel);

		assertThat(sent.getRequest(), equalTo(LedgerWireFixtures.request(3)));
		channel.writeInbound(encoded(LedgerBlockBatch.builder().requestId(sent.getRequestId())
			.batch(LedgerWireFixtures.emptyBatch()).build()));

		assertThat(reply.getNow(null), equalTo(LedgerWireFixtures.emptyBatch()));
	}

	@Example
	public void buildsFreshHandlersWithACodecForEveryLedgerType() {
		final List<ChannelHandler> first = LedgerPipeline.handlers();
		final List<ChannelHandler> second = LedgerPipeline.handlers();

		assertThat(first.size(), equalTo(second.size()));

		for (int i = 0; i < first.size(); i++) {
			assertThat(first.get(i), not(sameInstance(second.get(i))));
			assertThat(first.get(i).getClass(), equalTo(second.get(i).getClass()));
		}

		assertThat(first.stream().<Object>map(Object::getClass).toList(), containsInAnyOrder(
			(Object) LedgerTransactionEncoder.class, LedgerTransactionDecoder.class,
			LedgerConsensusEncoder.class, LedgerConsensusDecoder.class,
			LedgerHeartbeatEncoder.class, LedgerHeartbeatDecoder.class,
			LedgerStatusEncoder.class, LedgerStatusDecoder.class, LedgerBlockRequestEncoder.class,
			LedgerBlockRequestDecoder.class, LedgerBlockBatchEncoder.class, LedgerBlockBatchDecoder.class,
			LedgerTransactionChannelHandler.class, LedgerConsensusChannelHandler.class,
			LedgerHeartbeatChannelHandler.class, LedgerStatusChannelHandler.class,
			LedgerBlockRequestChannelHandler.class, StorageResponseChannelHandler.class));
	}

	@Example
	public void announcesAChannelToTheDriverOnce() {
		final EmbeddedChannel channel = streamFrom(PEER);
		final Consumer<Channel> announce = new LedgerStatusSchedule().getConsumer();

		announce.accept(channel);
		announce.accept(channel);
		announce.accept(channel);

		assertThat(calls.stream().map(Call::method).toList(), contains("onPeerConnected"));
		assertThat(calls.get(0).from().id(), equalTo(PEER));
	}

	@Example
	public void announcesAChannelWhenTheDriverComesAndToEachDriverOnce() {
		final EmbeddedChannel channel = streamFrom(PEER);
		final Consumer<Channel> announce = new LedgerStatusSchedule().getConsumer();

		gateway.detach();
		announce.accept(channel);
		assertThat(calls.isEmpty(), is(true));

		gateway.attach(aDriver());
		announce.accept(channel);
		announce.accept(channel);
		assertThat(calls.stream().map(Call::method).toList(), contains("onPeerConnected"));

		gateway.attach(aDriver());
		announce.accept(channel);
		assertThat(calls.stream().map(Call::method).toList(), contains("onPeerConnected", "onPeerConnected"));
	}

	/* The id of a stream is read at each use. A remote one is the same before and after its Hello is read, so nothing
	   that the driver keeps under it is left behind; a local one is renamed once, by the port it says it listens on. */
	@Example
	public void keepsTheIdOfARemoteStreamWhenItsHelloIsReadAndRenamesALocalOneOnce() {
		final EmbeddedChannel remote = streamFrom(PEER);
		final EmbeddedChannel local = streamFrom("127.0.0.1");
		final LedgerPeer remotePeer = gateway.peerOf(remote, () -> RemoteAddress.of(remote));
		final LedgerPeer localPeer = gateway.peerOf(local, () -> RemoteAddress.of(local));

		assertThat(remotePeer.id(), equalTo(PEER));
		assertThat(localPeer.id(), equalTo("127.0.0.1:" + PORT));

		RemoteAddress.listensOn(remote, 4444);
		RemoteAddress.listensOn(local, 4444);
		RemoteAddress.listensOn(local, 5555);

		assertThat(remotePeer.id(), equalTo(PEER));
		assertThat(localPeer.id(), equalTo("127.0.0.1:4444"));
	}

	/* The gate: a stream whose connection did not settle on the ledger protocol is not one to speak the ledger over */
	@Property(tries = 10)
	public void closesAStreamThatSendsTheLedgerWithoutHavingAgreedToIt(
		@ForAll @IntRange(min = 0, max = KINDS - 1) int kind, @ForAll @IntRange(min = 0, max = 100) int seed) {

		final EmbeddedChannel channel = streamOf(connectionWithoutTheLedgerFrom(PEER), new Remainder());
		final EmbeddedChannel other = streamFrom(OTHER);

		channel.writeInbound(frameOfKind(kind, seed));
		assertThat(channel.isOpen(), is(false));
		assertThat(calls.isEmpty(), is(true));
		assertThat(channel.outboundMessages().isEmpty(), is(true));

		other.writeInbound(encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build()));
		assertThat(calls.stream().map(Call::method).toList(), contains("onStatus"));
	}

	@Example
	public void closesAStreamThatSendsTheLedgerWithoutHavingAgreedToItAlsoWithNoDriver() {
		final EmbeddedChannel channel = streamOf(connectionWithoutTheLedgerFrom(PEER), new Remainder());

		gateway.detach();
		channel.writeInbound(encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(1)).build()));

		assertThat(channel.isOpen(), is(false));
	}

	@Example
	public void tellsTheDriverOfNoStreamThatDidNotAgreeToTheLedger() {
		final EmbeddedChannel agreeing = streamFrom(PEER);
		final EmbeddedChannel unagreeing = streamOf(connectionWithoutTheLedgerFrom(OTHER), new Remainder());
		final Consumer<Channel> announce = new LedgerStatusSchedule().getConsumer();

		announce.accept(unagreeing);
		announce.accept(agreeing);
		announce.accept(unagreeing);

		assertThat(calls.stream().map(Call::method).toList(), contains("onPeerConnected"));
		assertThat(calls.get(0).from().id(), equalTo(PEER));
		assertThat(unagreeing.outboundMessages().isEmpty(), is(true));
	}

	@Example
	public void listsOnlyTheStreamsThatAgreedToTheLedgerAsPeers() {
		final EmbeddedChannel agreeing = streamFrom(PEER);
		final EmbeddedChannel unagreeing = streamOf(connectionWithoutTheLedgerFrom(OTHER), new Remainder());

		agreeing.writeInbound(encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(1)).build()));

		final LedgerPeer unheard = gateway.peerOf(unagreeing, () -> RemoteAddress.of(unagreeing));

		assertThat(gateway.network().peers().contains(unheard), is(false));
		assertThat(gateway.network().peers().stream().anyMatch(peer -> PEER.equals(peer.id())), is(true));
		assertThat(gateway.speaks(agreeing), is(true));
		assertThat(gateway.speaks(unagreeing), is(false));
	}

	@Example
	public void makesAStreamSomeoneToSendToOnlyOnceItHasSpoken() {
		final EmbeddedChannel channel = streamFrom(PEER);
		final LedgerPeer peer = gateway.peerOf(channel, () -> RemoteAddress.of(channel));

		new LedgerStatusSchedule().getConsumer().accept(channel);
		assertThat(gateway.network().peers().contains(peer), is(false));

		channel.writeInbound(encoded(LedgerStatus.builder().status(LedgerWireFixtures.status(1)).build()));
		assertThat(gateway.network().peers().contains(peer), is(true));
	}

	@Example
	public void runsAtOnceWhenTheChannelIsMadeAndThenEveryTenSeconds() {
		final LedgerStatusSchedule schedule = new LedgerStatusSchedule();

		assertThat(schedule.isExecuteOnCreation(), is(true));
		assertThat(schedule.getPeriod(), equalTo(10));
		assertThat(schedule.getTimeUnit(), equalTo(TimeUnit.SECONDS));
	}
}
