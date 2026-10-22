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

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockBatch;
import org.unigrid.hedgehog.model.network.packet.LedgerBlockRequest;
import org.unigrid.hedgehog.model.network.packet.LedgerConsensus;
import org.unigrid.hedgehog.model.network.packet.LedgerHeartbeat;
import org.unigrid.hedgehog.model.network.packet.LedgerStatus;
import org.unigrid.hedgehog.model.network.packet.LedgerTransaction;

/* The other end of one stream. It is named by the remote address, and by the /64 prefix for IPv6, as an attacker
   with a /64 holds 2^64 addresses and must still share one limit. The quota of an IPv6 peer is its /48 prefix, the
   site a holder is given, in which it can number 2^16 subnets of /64: they share one limit more, while each is still
   a peer of its own, so that the nodes of one hosting provider are not taken for one node, nor cut off together.
   A node on this host is named by the port it listens on as well, as the nodes of one host share an address and none
   of them is an attacker that changes it. The address is read when it is asked for, as it can be learned only after
   the stream exists: a stream that a peer opened is first named by the port it came from, and by the port it listens
   on once its Hello has been read, which is the first thing it sends. The limit protects this node and authenticates
   no one. */
@Slf4j
@RequiredArgsConstructor
public final class NettyLedgerPeer implements LedgerPeer {
	private static final int IPV6_LENGTH = 16;
	private static final int IPV6_PREFIX_LENGTH = 8;
	private static final int IPV6_SITE_LENGTH = 6;
	private static final int IPV4_LENGTH = 4;
	private static final int IPV4_MAPPED_ZEROS = 10;

	/* The most messages that may wait on the event loop to be queued. Each is a packet around an object that the peers
	   share, so it costs little, and a loop that is not stalled has far fewer waiting than the votes of a lane. */
	static final int MAX_AWAITING = OutboundQueue.VOTES_PER_LANE;

	private final Channel channel;
	private final Supplier<Optional<InetSocketAddress>> remote;
	private final PendingRequests pending;
	private final long order;
	private final Predicate<Channel> negotiated;
	private final Predicate<Channel> outbound;
	private final Runnable dropped;
	private final AtomicReference<LedgerDriver> announcedTo = new AtomicReference<>();
	private final AtomicBoolean spoken = new AtomicBoolean();
	private final OutboundQueue queue = new OutboundQueue();
	private final AtomicInteger awaiting = new AtomicInteger();
	private volatile String listedAs;
	private boolean watching;

	/* An IPv4 address that comes as an IPv6 one, ::ffff:a.b.c.d, is the IPv4 address: read as IPv6 every such peer would
	   have the zeros of the same /64 prefix, and share one limit */
	@SneakyThrows
	public static InetAddress plain(final InetAddress address) {
		final byte[] bytes = address.getAddress();

		if (bytes.length == IPV6_LENGTH && IntStream.range(0, IPV4_MAPPED_ZEROS).allMatch(i -> bytes[i] == 0)
			&& bytes[IPV4_MAPPED_ZEROS] == -1 && bytes[IPV4_MAPPED_ZEROS + 1] == -1) {

			return InetAddress.getByAddress(Arrays.copyOfRange(bytes, IPV6_LENGTH - IPV4_LENGTH, IPV6_LENGTH));
		}

		return address;
	}

	public static String idOf(final InetSocketAddress socket) {
		final InetAddress address = plain(socket.getAddress());
		final byte[] bytes = address.getAddress();

		if (address.isLoopbackAddress()) {
			return address.getHostAddress() + ":" + socket.getPort();
		}

		if (bytes.length == IPV6_LENGTH) {
			return HexFormat.of().formatHex(bytes, 0, IPV6_PREFIX_LENGTH) + "/64";
		}

		return address.getHostAddress();
	}

	public static String quotaOf(final InetSocketAddress socket) {
		final InetAddress address = plain(socket.getAddress());
		final byte[] bytes = address.getAddress();

		return bytes.length == IPV6_LENGTH && !address.isLoopbackAddress()
			? HexFormat.of().formatHex(bytes, 0, IPV6_SITE_LENGTH) + "/48" : idOf(socket);
	}

	@Override
	public String id() {
		return remote.get().map(NettyLedgerPeer::idOf).orElseGet(() -> channel.id().asLongText());
	}

	@Override
	public String quota() {
		return remote.get().map(NettyLedgerPeer::quotaOf).orElseGet(this::id);
	}

	/* A stream this node opened, which is what dialling a node does: the driver asks the nodes it chose before
	   those that connected to it */
	@Override
	public boolean dialled() {
		return outbound.test(channel);
	}

	Channel channel() {
		return channel;
	}

	/* Which of the streams of one node was made last, for the network to choose between them */
	long order() {
		return order;
	}

	/* Whether the connection of the stream settled on the ledger protocol, the only kind of stream that is sent anything
	   of the ledger: any other end may be a node that would take it for something else */
	boolean speaksLedger() {
		return negotiated.test(channel);
	}

	/* True the first time for each driver, so a driver is told of a connection once */
	boolean firstAnnouncementTo(final LedgerDriver driver) {
		return announcedTo.getAndSet(driver) != driver;
	}

	/* True once, the first time that the stream says anything of the ledger protocol */
	boolean firstWord() {
		return !spoken.getAndSet(true);
	}

	/* The id that the network holds the stream under while it is one of the speakers it keeps, and null when it is
	   not: the id is read once, so that finding the streams of one id costs no more than comparing strings */
	String listedAs() {
		return listedAs;
	}

	void listAs(final String id) {
		listedAs = id;
	}

	void unlist() {
		listedAs = null;
	}

	/* What the stream does not take now waits for it, the votes ahead of the rest (see OutboundQueue), and is lost only
	   when the lane of it is full (the oldest vote or the new message of the rest), when too much waits to be queued
	   (see post) or when the stream closes, as the protocol is made to cope with. The stream is left alone when a
	   write fails: it carries every protocol, and a write that fails because it is closing is a race, not news.
	   Any thread may send, and the queue is touched on the event loop of the stream only. */
	@Override
	public void send(final Object wire) {
		final Packet packet = packetOf(wire);

		if (!speaksLedger()) {
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: it does not speak the ledger");
			return;
		}

		final OutboundQueue.Lane lane = OutboundQueue.Lane.of(wire);
		final int size = OutboundQueue.sizeOf(wire);

		if (channel.eventLoop().inEventLoop()) {
			enqueue(lane, packet, size);
		} else {
			post(lane, packet, size);
		}
	}

	/* The event loop is shared by the streams of a connection and may stall, and what is posted to it waits there
	   before the bound of a lane applies, so the number of messages that wait to be queued is bounded as well */
	private void post(final OutboundQueue.Lane lane, final Packet packet, final int size) {
		if (awaiting.incrementAndGet() > MAX_AWAITING) {
			awaiting.decrementAndGet();
			dropped.run();
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: too much waits to be queued");
			return;
		}

		try {
			channel.eventLoop().execute(() -> {
				awaiting.decrementAndGet();
				enqueue(lane, packet, size);
			});
		} catch (RejectedExecutionException ex) {
			awaiting.decrementAndGet();
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: its stream has shut down");
		}
	}

	private void enqueue(final OutboundQueue.Lane lane, final Packet packet, final int size) {
		if (!channel.isActive()) {
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: its stream is closed");
		} else {
			final int lost = queue.offer(lane, packet, size);

			IntStream.range(0, lost).forEach(n -> dropped.run());

			if (lost > 0) {
				log.atDebug().addArgument(this::id).addArgument(lane)
					.log("Dropped a message for {}: too much waits in its {} lane");
			}

			resumeWhenWritable();
			drain();
		}
	}

	/* The stream says when it takes more, and what waits is dropped with the stream */
	private void resumeWhenWritable() {
		if (!watching) {
			watching = true;
			channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
				@Override
				public void channelWritabilityChanged(final ChannelHandlerContext ctx) throws Exception {
					drain();
					super.channelWritabilityChanged(ctx);
				}
			});
			channel.closeFuture().addListener(closed -> queue.clear());
		}
	}

	private void drain() {
		boolean wrote = false;

		while (channel.isWritable() && writeNext()) {
			wrote = true;
		}

		if (wrote) {
			channel.flush();
		}
	}

	private boolean writeNext() {
		return queue.poll().map(packet -> channel.write(packet).addListener(this::written)).isPresent();
	}

	/* A write that completes is a reason to look at the stream again, as a stream may take more without saying so */
	private void written(final Future<?> written) {
		if (!written.isSuccess()) {
			log.atDebug().addArgument(this::id).addArgument(() -> String.valueOf(written.cause()))
				.log("Could not send to {}: {}");
		}

		if (!queue.isEmpty()) {
			channel.eventLoop().execute(this::drain);
		}
	}

	/* For the tests: how many wait for the stream */
	int waiting() {
		return queue.size();
	}

	private static Packet packetOf(final Object wire) {
		return switch (wire) {
			case Transaction transaction -> LedgerTransaction.builder().transaction(transaction).build();
			case Message message -> LedgerConsensus.builder().message(message).build();
			case Heartbeat heartbeat -> LedgerHeartbeat.builder().heartbeat(heartbeat).build();
			case Status status -> LedgerStatus.builder().status(status).build();
			default -> throw new IllegalArgumentException("Not an object of the ledger protocol: " + wire);
		};
	}

	/* The driver paces and retries its requests, so one that cannot be answered is failed at once: when the write
	   fails and when the stream closes, not only when the timeout of the pending request runs out */
	@Override
	public CompletableFuture<BlockBatch> request(final BlockRequest request) {
		if (!speaksLedger()) {
			return CompletableFuture.failedFuture(
				new IllegalStateException("The other end does not speak the ledger"));
		}

		final long requestId = pending.nextRequestId();
		final CompletableFuture<LedgerBlockBatch> reply = pending.register(requestId, channel,
			LedgerBlockBatch.class);
		final ChannelFutureListener failOnClose = closed -> pending.fail(requestId, new ClosedChannelException());

		channel.closeFuture().addListener(failOnClose);
		channel.writeAndFlush(LedgerBlockRequest.builder().requestId(requestId).request(request).build())
			.addListener(written -> {
				if (!written.isSuccess()) {
					pending.fail(requestId, written.cause());
				}
			});

		return reply.whenComplete((batch, error) -> channel.closeFuture().removeListener(failOnClose))
			.thenApply(LedgerBlockBatch::getBatch);
	}
}
