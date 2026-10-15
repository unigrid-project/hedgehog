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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
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

   Whoever closes the driver takes it out with detach() first. What a driver that has shut down gets is rejected, and
   that is dropped here too: the stream of a peer carries every protocol, and is not closed for a ledger that is going. */
@Slf4j
@ApplicationScoped
public class LedgerGateway {
	@Inject private PendingRequests pending;
	@Inject private Topology topology;

	private NettyLedgerNetwork network;
	private volatile LedgerDriver driver;

	@PostConstruct
	private void init() {
		network = new NettyLedgerNetwork(topology::cloneNodes, pending, NettyLedgerNetwork::openedHere);
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

	public void onTransaction(final LedgerPeer from, final Transaction transaction) {
		ifAttached(attached -> attached.onTransaction(from, transaction));
	}

	public void onMessage(final LedgerPeer from, final Message message) {
		ifAttached(attached -> attached.onMessage(from, message));
	}

	public void onHeartbeat(final LedgerPeer from, final Heartbeat heartbeat) {
		ifAttached(attached -> attached.onHeartbeat(from, heartbeat));
	}

	public void onStatus(final LedgerPeer from, final Status status) {
		ifAttached(attached -> attached.onStatus(from, status));
	}

	/* A request nobody answers gets no reply, which is what the driver does with one it refuses */
	public CompletableFuture<BlockBatch> onBlockRequest(final LedgerPeer from, final BlockRequest request) {
		final LedgerDriver attached = driver;

		if (attached == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("No ledger is attached"));
		}

		try {
			return attached.onBlockRequest(from, request);
		} catch (RejectedExecutionException ex) {
			log.atDebug().log("Dropped a block request: the ledger takes no more");
			return CompletableFuture.failedFuture(ex);
		}
	}

	/* The driver is told of a stream once, and the stream is told this node's status by the driver */
	public void peerConnected(final NettyLedgerPeer peer) {
		ifAttached(attached -> {
			if (peer.firstAnnouncementTo(attached)) {
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
