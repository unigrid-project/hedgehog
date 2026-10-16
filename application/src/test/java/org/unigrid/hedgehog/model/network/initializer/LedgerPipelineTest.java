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

package org.unigrid.hedgehog.model.network.initializer;

import jakarta.enterprise.inject.spi.CDI;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.objenesis.ObjenesisStd;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.service.ledger.LedgerWireFixtures.frameOf;
import org.unigrid.hedgehog.client.p2p.P2PClient;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;
import org.unigrid.hedgehog.service.ledger.LedgerTestChain;
import org.unigrid.hedgehog.service.ledger.LedgerWireFixtures;

public class LedgerPipelineTest extends BaseServerTest {
	private static final String LOCAL = "127.0.0.1:";
	private static final String CONNECTED = "onPeerConnected";
	private static final BlockBatch BATCH = new BlockBatch(LedgerTestChain.blocks(3));

	private record Call(String method, LedgerPeer from, Object argument) {
	}

	private final List<Call> calls = new CopyOnWriteArrayList<>();
	private LedgerGateway gateway;
	private LedgerDriver driver;
	private volatile CompletableFuture<BlockBatch> answer;

	@Override
	protected boolean runsLedger() {
		return true;
	}

	@BeforeTry
	private void attachAStubDriver() {
		calls.clear();
		answer = CompletableFuture.completedFuture(BATCH);
		new MockUp<LedgerDriver>() {
			@Mock public void onTransaction(LedgerPeer from, Transaction transaction, int bytes) {
				calls.add(new Call("onTransaction", from, transaction));
			}

			@Mock public void onMessage(LedgerPeer from, Message message, int bytes) {
				calls.add(new Call("onMessage", from, message));
			}

			@Mock public void onHeartbeat(LedgerPeer from, Heartbeat heartbeat, int bytes) {
				calls.add(new Call("onHeartbeat", from, heartbeat));
			}

			@Mock public void onStatus(LedgerPeer from, Status status, int bytes) {
				calls.add(new Call("onStatus", from, status));
			}

			@Mock public CompletableFuture<BlockBatch> onBlockRequest(LedgerPeer from, BlockRequest request,
				int bytes) {

				calls.add(new Call("onBlockRequest", from, request));
				return answer;
			}

			@Mock public void onPeerConnected(LedgerPeer from) {
				calls.add(new Call(CONNECTED, from, null));
			}
		};

		/* Every test server runs in its own container, so the gateway is the one the handlers resolve from */
		gateway = CDI.current().select(LedgerGateway.class).get();
		driver = new ObjenesisStd().newInstance(LedgerDriver.class);
		gateway.attach(driver);
	}

	@AfterTry
	private void detachTheDriver() {
		gateway.detach();
	}

	private static P2PClient clientOf(final TestServer server) throws Exception {
		return new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());
	}

	/* A server that closes a stream ends what it sends, which is the end of the stream for a client that waits */
	private static boolean isOpen(final P2PClient client) {
		return client.getChannel().isActive() && !client.getChannel().isInputShutdown();
	}

	/* What the driver was told by the peers, not that a stream was announced, in the order it was told */
	private List<Call> heard() {
		return calls.stream().filter(call -> !CONNECTED.equals(call.method())).toList();
	}

	private void sendAllKinds(final P2PClient client, final int seed) {
		client.send(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build());
		client.send(LedgerTransaction.builder().transaction(LedgerWireFixtures.transaction(seed)).build());
		client.send(LedgerHeartbeat.builder().heartbeat(LedgerWireFixtures.heartbeat(seed)).build());
		client.send(LedgerConsensus.builder().message(LedgerWireFixtures.message(seed)).build());
	}

	@SneakyThrows
	private LedgerBlockBatch request(final P2PClient client, final int seed) {
		final PendingRequests pending = CDI.current().select(PendingRequests.class).get();
		final long id = pending.nextRequestId();
		final CompletableFuture<LedgerBlockBatch> reply = pending.register(id, client.getChannel(),
			LedgerBlockBatch.class);

		client.send(LedgerBlockRequest.builder().requestId(id).request(LedgerWireFixtures.request(seed)).build());
		return reply.get(10, TimeUnit.SECONDS);
	}

	private static List<String> hashesOf(final BlockBatch batch) {
		return batch.blocks().stream().map(Block::hash).map(HexFormat.of()::formatHex).toList();
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void deliversWhatAClientSendsToTheDriverAsTheSameObjects(
		@ForAll("provideTestServers") List<TestServer> servers, @ForAll @IntRange(min = 0, max = 100) int seed)
		throws Exception {

		for (final TestServer server : servers) {
			final P2PClient client = clientOf(server);

			calls.clear();

			try {
				sendAllKinds(client, seed);
				await().until(() -> heard().size() == 4);

				assertThat(heard().stream().map(Call::method).toList(),
					contains("onStatus", "onTransaction", "onHeartbeat", "onMessage"));
				assertThat(heard().stream().map(Call::argument).toList(),
					contains(LedgerWireFixtures.status(seed), LedgerWireFixtures.transaction(seed),
						LedgerWireFixtures.heartbeat(seed), LedgerWireFixtures.message(seed)));
				assertThat(heard().stream().map(call -> call.from().id()).distinct().count(), is(1L));
				assertThat(heard().get(0).from().id(), startsWith(LOCAL));
			} finally {
				client.closeDirty();
			}
		}
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void answersABlockRequestWithTheBatchOfTheDriver(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll @IntRange(min = 0, max = 100) int seed) throws Exception {

		for (final TestServer server : servers) {
			final P2PClient client = clientOf(server);

			calls.clear();

			try {
				final LedgerBlockBatch reply = request(client, seed);

				assertThat(hashesOf(reply.getBatch()), equalTo(hashesOf(BATCH)));
				assertThat(heard().stream().map(Call::method).toList(), contains("onBlockRequest"));
				assertThat(heard().get(0).argument(), equalTo(LedgerWireFixtures.request(seed)));
			} finally {
				client.closeDirty();
			}
		}
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void leavesARefusedRequestUnansweredAndTheStreamOpen(
		@ForAll("provideTestServers") List<TestServer> servers) throws Exception {

		answer = CompletableFuture.failedFuture(new RejectedExecutionException("The request is refused"));

		for (final TestServer server : servers) {
			final P2PClient client = clientOf(server);

			try {
				final PendingRequests pending = CDI.current().select(PendingRequests.class).get();
				final long id = pending.nextRequestId();
				final CompletableFuture<LedgerBlockBatch> reply = pending.register(id, client.getChannel(),
					LedgerBlockBatch.class);

				client.send(LedgerBlockRequest.builder().requestId(id).request(LedgerWireFixtures.request(1))
					.build());
				await().until(() -> heard().stream()
					.anyMatch(call -> "onBlockRequest".equals(call.method())));
				await().during(Duration.ofMillis(500)).until(() -> !reply.isDone());

				assertThat(isOpen(client), is(true));
				pending.fail(id, new RejectedExecutionException("done with the request"));
			} finally {
				client.closeDirty();
			}
		}
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void dropsWhatItHearsWhileNoDriverIsAttachedAndKeepsTheStreamOpen(
		@ForAll("provideTestServers") List<TestServer> servers, @ForAll @IntRange(min = 0, max = 100) int seed)
		throws Exception {

		for (final TestServer server : servers) {
			final P2PClient client = clientOf(server);

			calls.clear();
			gateway.detach();

			try {
				final PendingRequests pending = CDI.current().select(PendingRequests.class).get();
				final long id = pending.nextRequestId();
				final CompletableFuture<LedgerBlockBatch> reply = pending.register(id, client.getChannel(),
					LedgerBlockBatch.class);

				sendAllKinds(client, seed);
				client.send(LedgerBlockRequest.builder().requestId(id)
					.request(LedgerWireFixtures.request(seed)).build());
				await().during(Duration.ofMillis(500)).until(() -> heard().isEmpty() && !reply.isDone());
				assertThat(isOpen(client), is(true));
				pending.fail(id, new RejectedExecutionException("done with the request"));

				gateway.attach(driver);
				client.send(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build());
				await().until(() -> heard().size() == 1);

				assertThat(heard().get(0).argument(), equalTo(LedgerWireFixtures.status(seed)));
			} finally {
				client.closeDirty();
			}
		}
	}

	private record Frame(Packet.Type type, byte[] body) {
	}

	/* Frames whose body is whole, as far as the header says, and is not what the type holds: a length above what the
	   type allows or below 0, an object that is not one, and a length that runs past the end of the frame */
	@Provide
	Arbitrary<Frame> malformedFrames() {
		return Arbitraries.of(
			new Frame(Packet.Type.LEDGER_TRANSACTION, new byte[] { 127, -1, -1, -1 }),
			new Frame(Packet.Type.LEDGER_TRANSACTION, new byte[] { -1, -1, -1, -1 }),
			new Frame(Packet.Type.LEDGER_TRANSACTION, new byte[] { 0, 0, 0, 3, 99, 0, 0 }),
			new Frame(Packet.Type.LEDGER_CONSENSUS, new byte[] { 127, -1, -1, -1 }),
			new Frame(Packet.Type.LEDGER_CONSENSUS, new byte[] { 0, 0, 0, 3, 99, 0, 0 }),
			new Frame(Packet.Type.LEDGER_TRANSACTION, new byte[] { 0, 0, 0, 100, 2, 0, 0 }),
			new Frame(Packet.Type.LEDGER_CONSENSUS, new byte[] { 0, 0, 0, 100, 1, 0, 0 }));
	}

	/* Batches that could not be read, from a peer that was not asked for one: they are left unread, and the stream,
	   which carries every protocol, is as it was */
	@Provide
	Arbitrary<Frame> unaskedBatches() {
		return Arbitraries.of(
			new Frame(Packet.Type.LEDGER_BLOCK_BATCH, new byte[] { 0, 0, 0, 0, 0, 0, 0, 1, 127, -1, -1, -1 }),
			new Frame(Packet.Type.LEDGER_BLOCK_BATCH, new byte[] { 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, -1, -1 }),
			new Frame(Packet.Type.LEDGER_BLOCK_BATCH,
				new byte[] { 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 100, 1, 2 }));
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void leavesAnUnaskedBatchUnreadAndKeepsTheStreamOpen(
		@ForAll("provideTestServers") List<TestServer> servers, @ForAll("unaskedBatches") Frame frame,
		@ForAll @IntRange(min = 0, max = 100) int seed) throws Exception {

		for (final TestServer server : servers) {
			final P2PClient sender = clientOf(server);

			calls.clear();

			try {
				sender.getChannel().writeAndFlush(frameOf(frame.type(), frame.body()));
				sender.send(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build());
				await().until(() -> heard().size() == 1);

				assertThat(heard().get(0).argument(), equalTo(LedgerWireFixtures.status(seed)));
				assertThat(isOpen(sender), is(true));
			} finally {
				sender.closeDirty();
			}
		}
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void closesOnlyTheStreamOfAMalformedFrameAndKeepsServing(
		@ForAll("provideTestServers") List<TestServer> servers, @ForAll("malformedFrames") Frame frame,
		@ForAll @IntRange(min = 0, max = 100) int seed) throws Exception {

		for (final TestServer server : servers) {
			final P2PClient damaged = clientOf(server);
			final P2PClient other = clientOf(server);

			calls.clear();

			try {
				damaged.getChannel().writeAndFlush(frameOf(frame.type(), frame.body()));
				await().until(() -> !isOpen(damaged));

				other.send(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build());
				await().until(() -> heard().size() == 1);

				assertThat(heard().get(0).argument(), equalTo(LedgerWireFixtures.status(seed)));
				assertThat(isOpen(other), is(true));
			} finally {
				damaged.closeDirty();
				other.closeDirty();
			}
		}
	}

	/* A frame shorter than its type needs cannot be told from the start of the next, so the stream is closed, and
	   nothing of it reaches the driver. Another stream is served as before. */
	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void closesTheStreamOfATruncatedFrameAndKeepsServingOthers(
		@ForAll("provideTestServers") List<TestServer> servers, @ForAll @IntRange(min = 0, max = 4) int length,
		@ForAll @IntRange(min = 0, max = 100) int seed) throws Exception {

		for (final TestServer server : servers) {
			final P2PClient truncating = clientOf(server);
			final P2PClient other = clientOf(server);

			calls.clear();

			try {
				truncating.getChannel().writeAndFlush(frameOf(Packet.Type.LEDGER_STATUS, new byte[length]));
				await().until(() -> !isOpen(truncating));
				assertThat(heard().isEmpty(), is(true));

				other.send(LedgerStatus.builder().status(LedgerWireFixtures.status(seed)).build());
				await().until(() -> heard().size() == 1);

				assertThat(heard().get(0).argument(), equalTo(LedgerWireFixtures.status(seed)));
				assertThat(isOpen(other), is(true));
			} finally {
				truncating.closeDirty();
				other.closeDirty();
			}
		}
	}
}
