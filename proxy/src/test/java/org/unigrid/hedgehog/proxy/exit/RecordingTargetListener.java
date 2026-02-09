/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.proxy.exit;

import io.netty.handler.codec.socksx.v5.Socks5CommandStatus;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/* Keeps what a connector reports, from whatever thread it reports it */
final class RecordingTargetListener implements TargetListener {
	static final long TIMEOUT_MILLIS = 5000;

	final CompletableFuture<Target> connected = new CompletableFuture<>();
	final CompletableFuture<Socks5CommandStatus> failed = new CompletableFuture<>();
	final CompletableFuture<Boolean> closed = new CompletableFuture<>();
	final CompletableFuture<Boolean> receivedAny = new CompletableFuture<>();
	private final ByteArrayOutputStream received = new ByteArrayOutputStream();
	private final List<String> events = new ArrayList<>();

	/* Waits until the condition holds, failing the test after the timeout */
	static void await(BooleanSupplier condition) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;

		while (!condition.getAsBoolean()) {
			if (System.currentTimeMillis() > deadline) {
				throw new AssertionError("Still waiting after " + TIMEOUT_MILLIS + " ms");
			}

			Thread.sleep(5);
		}
	}

	Target target() throws Exception {
		return connected.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
	}

	synchronized byte[] received() {
		return received.toByteArray();
	}

	/* The first count bytes received, once they arrived */
	byte[] received(int count) throws InterruptedException {
		await(() -> received().length >= count);
		return received();
	}

	/* Every call in the order it came, with the chunks of one run of received calls counted as one */
	synchronized List<String> events() {
		return List.copyOf(events);
	}

	private synchronized void record(String event) {
		if (!event.equals("received") || events.isEmpty() || !events.get(events.size() - 1).equals(event)) {
			events.add(event);
		}
	}

	@Override
	public void connected(Target target) {
		record("connected");
		connected.complete(target);
	}

	@Override
	public void failed(Socks5CommandStatus status) {
		record("failed " + status);
		failed.complete(status);
	}

	@Override
	public synchronized void received(byte[] bytes) {
		record("received");
		received.writeBytes(bytes);
		receivedAny.complete(true);
	}

	@Override
	public void closed() {
		record("closed");
		closed.complete(true);
	}
}
