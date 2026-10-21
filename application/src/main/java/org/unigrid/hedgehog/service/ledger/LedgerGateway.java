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
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.BlockBatch;
import org.unigrid.hedgehog.ledger.BlockRequest;
import org.unigrid.hedgehog.ledger.Heartbeat;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerNetwork;
import org.unigrid.hedgehog.ledger.LedgerPeer;
import org.unigrid.hedgehog.ledger.Message;
import org.unigrid.hedgehog.ledger.Status;
import org.unigrid.hedgehog.ledger.Transaction;
import org.unigrid.hedgehog.model.network.PendingRequests;
import org.unigrid.hedgehog.model.network.Topology;

/* Where the handlers of the pipeline hand what peers send to the driver. A node without a ledger carries the pipeline
   all the same, and then what peers send is dropped here, so the rest of the node never has to ask.

   The ledger protocol token of the handshake, Network.LEDGER_PROTOCOL, is the capability gate: a stream is one to
   speak the ledger over only if its connection settled on it (speaks, LedgerProtocol). The network lists no other
   stream as a peer, the peers send nothing over one, a stream announced to the driver is one that did, and the handlers
   close a stream that sends the ledger without having settled on it.

   Whoever closes the driver takes it out with detach() first. What a driver that has shut down gets is rejected, and
   that is dropped here too: the stream of a peer carries every protocol, and is not closed for a ledger that is going. */
@Slf4j
@ApplicationScoped
public class LedgerGateway {
	private static final long SAY_EVERY_MILLIS = 60_000;

	@Inject private PendingRequests pending;
	@Inject private Topology topology;

	private NettyLedgerNetwork network;
	private volatile LedgerDriver driver;
	private final AtomicLong saidAt = new AtomicLong();
	private volatile LongSupplier clock = System::currentTimeMillis;

	@PostConstruct
	private void init() {
		network = new NettyLedgerNetwork(topology::cloneNodes, pending, NettyLedgerNetwork::openedHere,
			LedgerProtocol::negotiatedOn);
	}

	/* Whether the connection of the stream settled on the ledger protocol, which is what makes a stream one to speak
	   the ledger over */
	public boolean speaks(final Channel stream) {
		return network.speaks(stream);
	}

	public LedgerNetwork network() {
		return network;
	}

	public void attach(final LedgerDriver driver) {
		this.driver = driver;
	}

	public void detach() {
		driver = null;
	}

	public NettyLedgerPeer peerOf(final Channel channel, final Supplier<Optional<InetSocketAddress>> remote) {
		return network.peerOf(channel, remote);
	}

	/* A stream is announced to the driver when it first says something at the latest, and before it is heard: the
	   driver counts the status of a peer it has not been told of as junk, and the schedule that announces the streams
	   may not have run for it yet, or no driver was attached then */
	public NettyLedgerPeer speakerOf(final Channel channel, final Supplier<Optional<InetSocketAddress>> remote) {
		final NettyLedgerPeer speaker = network.speakerOf(channel, remote);

		peerConnected(speaker);
		return speaker;
	}

	/* `bytes` in what follows is the size of the frame that the packet came in */
	public void onTransaction(final LedgerPeer from, final Transaction transaction, final int bytes) {
		ifAttached(attached -> attached.onTransaction(from, transaction, bytes));
	}

	public void onMessage(final LedgerPeer from, final Message message, final int bytes) {
		ifAttached(attached -> attached.onMessage(from, message, bytes));
	}

	public void onHeartbeat(final LedgerPeer from, final Heartbeat heartbeat, final int bytes) {
		ifAttached(attached -> attached.onHeartbeat(from, heartbeat, bytes));
	}

	public void onStatus(final LedgerPeer from, final Status status, final int bytes) {
		ifAttached(attached -> attached.onStatus(from, status, bytes));
	}

	/* A request nobody answers gets no reply, which is what the driver does with one it refuses */
	public CompletableFuture<BlockBatch> onBlockRequest(final LedgerPeer from, final BlockRequest request,
		final int bytes) {

		final LedgerDriver attached = driver;

		if (attached == null) {
			saySometimesThatNoLedgerRuns();
			return CompletableFuture.completedFuture(new BlockBatch(List.of()));
		}

		try {
			return attached.onBlockRequest(from, request, bytes);
		} catch (RejectedExecutionException ex) {
			log.atDebug().log("Dropped a block request: the ledger takes no more");
			return CompletableFuture.failedFuture(ex);
		}
	}

	/* A node that offers the ledger protocol but whose ledger does not run, as it still waits for its snapshot or
	   refused to start, is asked for blocks by peers that sync. A peer waits for its timeout for a request that is not
	   answered, so it is answered with nothing at once: an empty batch ends its sync with this node, and it asks
	   another. */
	private void saySometimesThatNoLedgerRuns() {
		final long now = clock.getAsLong();
		final long before = saidAt.get();

		if (now - before >= SAY_EVERY_MILLIS && saidAt.compareAndSet(before, now)) {
			log.atInfo().log("Answered a request for blocks with none, as no ledger is running on this node");
		}
	}

	/* For the tests of this package */
	void clockOf(final LongSupplier clock) {
		this.clock = clock;
	}

	/* The driver is told of a stream once, and the stream is told this node's status by the driver. Whichever comes
	   first tells it, the schedule of the streams or the first word of the stream. */
	public void peerConnected(final NettyLedgerPeer peer) {
		ifAttached(attached -> {
			if (peer.speaksLedger() && peer.firstAnnouncementTo(attached)) {
				attached.onPeerConnected(peer);
			}
		});
	}

	private void ifAttached(final Consumer<LedgerDriver> call) {
		final LedgerDriver attached = driver;

		if (attached != null) {
			try {
				call.accept(attached);
			} catch (RejectedExecutionException ex) {
				log.atDebug().log("Dropped what a peer sent: the ledger takes no more");
			}
		}
	}
}
