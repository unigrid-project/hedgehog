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

package org.unigrid.hedgehog.s3.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;

/*
   The body of a request as its resource reads it. The body holds the channel back from reading once the high mark
   of it waits here and lets go below the low mark, so a client sending faster than the resource stores is held back
   by its own TCP window rather than by the memory of the server. Every buffer is released once it is read,
   discarded or failed.
*/
@RequiredArgsConstructor
final class S3RequestBody extends InputStream {
	private final Channel channel;
	private final S3ReadGate gate;
	private final int highWater;
	private final int lowWater;
	private final Duration stallTimeout;
	private final Deque<ByteBuf> buffers = new ArrayDeque<>();

	private int buffered;
	private long received;
	private boolean ended;
	private boolean discarded;
	private IOException failure;

	synchronized void offer(final ByteBuf content) {
		received += content.readableBytes();

		if (discarded || failure != null || !content.isReadable()) {
			content.release();
			return;
		}

		buffers.add(content);
		buffered += content.readableBytes();

		if (buffered >= highWater) {
			gate.hold(this);
		}

		notifyAll();
	}

	synchronized void end() {
		ended = true;
		notifyAll();
	}

	/* Whether the client has at most the limit left to send of a body of the given length, or -1 when unknown */
	synchronized boolean remainsWithin(final long length, final long limit) {
		return ended || length >= 0 && length - received <= limit;
	}

	synchronized void fail(final IOException cause) {
		if (!discarded && failure == null) {
			failure = cause;
			release();
			gate.release(this);
		}
	}

	/* Drops whatever the resource left unread, and reads on only if the rest is to be drained for the next request */
	synchronized void discard(final boolean drain) {
		discarded = true;
		release();

		if (drain) {
			gate.release(this);
		} else {
			gate.hold(this);
		}
	}

	private void release() {
		buffers.forEach(ByteBuf::release);
		buffers.clear();
		buffered = 0;
		notifyAll();
	}

	private boolean isAwaited() {
		return buffers.isEmpty() && !ended && !discarded && failure == null;
	}

	private void await(final long nanos) throws InterruptedIOException {
		try {
			TimeUnit.NANOSECONDS.timedWait(this, nanos);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
	}

	private void awaitContent() throws IOException {
		final long deadline = System.nanoTime() + stallTimeout.toNanos();

		while (isAwaited()) {
			final long left = deadline - System.nanoTime();

			if (left <= 0) {
				fail(new IOException("The request body stalled for " + stallTimeout.toMillis() + " ms"));
				channel.close();
			} else {
				await(left);
			}
		}

		if (failure != null) {
			throw new IOException(failure);
		}
	}

	@Override
	public int read() throws IOException {
		final byte[] single = new byte[1];

		return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
	}

	@Override
	public synchronized int read(final byte[] buffer, final int offset, final int length) throws IOException {
		Objects.checkFromIndexSize(offset, length, buffer.length);

		if (length == 0) {
			return 0;
		}

		awaitContent();

		final ByteBuf head = buffers.peek();

		if (head == null) {
			return -1;
		}

		final int read = Math.min(length, head.readableBytes());

		head.readBytes(buffer, offset, read);
		buffered -= read;

		if (!head.isReadable()) {
			buffers.remove().release();
		}

		if (buffered <= lowWater) {
			gate.release(this);
		}

		return read;
	}

	@Override
	public synchronized int available() {
		return buffered;
	}

	/* The response decides whether the rest is drained, so reading stops until then unless it already decided */
	@Override
	public synchronized void close() {
		if (!discarded) {
			discard(false);
		}
	}
}
