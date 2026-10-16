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

package org.unigrid.hedgehog.ledger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/* The connection between two real drivers: what one sends is given to the other, who sees the first one by the name
   of its end of the link, and a request for blocks is put to the other driver's own limits and answers */
final class DriverLink implements LedgerPeer {
	private final String id;
	private final AtomicInteger requests = new AtomicInteger();
	private final AtomicInteger statuses = new AtomicInteger();
	private LedgerDriver target;
	private DriverLink back;
	private ManualScheduler scheduler;
	private long lagMillis;

	private DriverLink(String id) {
		this.id = id;
	}

	/* Two ends: the first is what `left` holds in its hand for `right`, and is named for `right` */
	static DriverLink[] connect(LedgerDriver left, String leftName, LedgerDriver right, String rightName) {
		final DriverLink forLeft = new DriverLink(rightName);
		final DriverLink forRight = new DriverLink(leftName);

		forLeft.target = right;
		forLeft.back = forRight;
		forRight.target = left;
		forRight.back = forLeft;
		return new DriverLink[] {forLeft, forRight};
	}

	/* The consensus messages that go through either end take this long, while a status does not: what a peer says of its
	   chain can then arrive before the decision it is about, as it can on a network */
	DriverLink lagging(ManualScheduler scheduler, long millis) {
		this.scheduler = scheduler;
		this.lagMillis = millis;
		return this;
	}

	/* How many requests for blocks were put through this end */
	int requests() {
		return requests.get();
	}

	/* How many statuses were sent through this end */
	int statuses() {
		return statuses.get();
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public void send(Object wire) {
		switch (wire) {
			case Transaction transaction -> target.onTransaction(back, transaction, DriverRig.FRAME);
			case Heartbeat heartbeat -> target.onHeartbeat(back, heartbeat, DriverRig.FRAME);
			case Message message -> deliver(message);
			case Status status -> {
				statuses.incrementAndGet();
				target.onStatus(back, status, DriverRig.FRAME);
			}
			default -> throw new IllegalArgumentException("Not a wire object: " + wire);
		}
	}

	private void deliver(Message message) {
		if (scheduler == null) {
			target.onMessage(back, message, DriverRig.FRAME);
		} else {
			scheduler.schedule(() -> target.onMessage(back, message, DriverRig.FRAME), lagMillis);
		}
	}

	@Override
	public CompletableFuture<BlockBatch> request(BlockRequest request) {
		requests.incrementAndGet();
		return target.onBlockRequest(back, request, DriverRig.FRAME);
	}
}
