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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
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
   with a /64 holds 2^64 addresses and must still share one limit. A node on this host is named by its port as well,
   as the nodes of one host share an address and none of them is an attacker that changes it. The address is read
   when it is asked for, as it can be learned only after the stream exists. The limit protects this node and
   authenticates no one. */
@RequiredArgsConstructor
public final class NettyLedgerPeer implements LedgerPeer {
	private static final int IPV6_LENGTH = 16;
	private static final int IPV6_PREFIX_LENGTH = 8;

	private final Channel channel;
	private final Supplier<Optional<InetSocketAddress>> remote;
	private final PendingRequests pending;
	private final AtomicReference<LedgerDriver> announcedTo = new AtomicReference<>();

	static String idOf(final InetSocketAddress socket) {
		final InetAddress address = socket.getAddress();
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

	Channel channel() {
		return channel;
	}

	/* True the first time for each driver, so a driver is told of a connection once */
	boolean firstAnnouncementTo(final LedgerDriver driver) {
		return announcedTo.getAndSet(driver) != driver;
	}

	@Override
	public void send(final Object wire) {
		channel.writeAndFlush(packetOf(wire)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
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
