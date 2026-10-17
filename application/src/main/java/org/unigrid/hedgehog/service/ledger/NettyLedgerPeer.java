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
   with a /64 holds 2^64 addresses and must still share one limit. A node on this host is named by the port it
   listens on as well, as the nodes of one host share an address and none of them is an attacker that changes it.
   The address is read when it is asked for, as it can be learned only after the stream exists: a stream that a peer
   opened is first named by the port it came from, and by the port it listens on once its Hello has been read, which
   is the first thing it sends. The limit protects this node and authenticates no one. */
@Slf4j
@RequiredArgsConstructor
public final class NettyLedgerPeer implements LedgerPeer {
	private static final int IPV6_LENGTH = 16;
	private static final int IPV6_PREFIX_LENGTH = 8;
	private static final int IPV4_LENGTH = 4;
	private static final int IPV4_MAPPED_ZEROS = 10;

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

	@Override
	public String id() {
		return remote.get().map(NettyLedgerPeer::idOf).orElseGet(() -> channel.id().asLongText());
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
	   when the lane of it is full or the stream closes, as the protocol is made to cope with. The stream is left alone
	   when a write fails: it carries every protocol, and a write that fails because it is closing is a race, not
	   news. Any thread may send, and the queue is touched on the event loop of the stream only. */
	@Override
	public void send(final Object wire) {
		final Packet packet = packetOf(wire);

		if (!speaksLedger()) {
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: it does not speak the ledger");
			return;
		}

		final OutboundQueue.Lane lane = OutboundQueue.Lane.of(wire);
		final int size = OutboundQueue.sizeOf(wire);

		try {
			onEventLoop(() -> enqueue(lane, packet, size));
		} catch (RejectedExecutionException ex) {
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: its stream has shut down");
		}
	}

	private void onEventLoop(final Runnable task) {
		if (channel.eventLoop().inEventLoop()) {
			task.run();
		} else {
			channel.eventLoop().execute(task);
		}
	}

	private void enqueue(final OutboundQueue.Lane lane, final Packet packet, final int size) {
		if (!channel.isActive()) {
			log.atDebug().addArgument(this::id).log("Dropped what was sent to {}: its stream is closed");
		} else if (queue.offer(lane, packet, size)) {
			resumeWhenWritable();
			drain();
		} else {
			dropped.run();
			log.atDebug().addArgument(this::id).addArgument(lane)
				.log("Dropped what was sent to {}: too much waits in its {} lane");
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
