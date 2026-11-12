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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/* A channel of a file on a disk that fills up, or of a process that is killed as it writes: it cuts and writes through
   the channel it is given until it has let its bytes through, and fails from there on. Only what cuts, moves, writes in
   order and forces is passed on, so that a write that goes another way fails a test rather than slips past the count. */
public final class FailingFileChannel extends FileChannel {
	public static final String REASON = "No space left on device";

	private final FileChannel channel;
	private final boolean cutting;
	private int allowance;

	private FailingFileChannel(FileChannel channel, boolean cutting, int allowance) {
		this.channel = channel;
		this.cutting = cutting;
		this.allowance = allowance;
	}

	/* Cuts, writes the bytes given and fails at the one after them */
	public static FileChannel afterBytes(FileChannel channel, int written) {
		return new FailingFileChannel(channel, true, written);
	}

	/* Fails before it has cut anything off */
	public static FileChannel atTheCut(FileChannel channel) {
		return new FailingFileChannel(channel, false, 0);
	}

	@Override
	public int write(ByteBuffer source) throws IOException {
		if (allowance == 0) {
			throw new IOException(REASON);
		}

		final int limit = source.limit();

		source.limit(Math.min(limit, source.position() + allowance));

		try {
			final int written = channel.write(source);

			allowance -= written;
			return written;
		} finally {
			source.limit(limit);
		}
	}

	@Override
	public FileChannel truncate(long size) throws IOException {
		if (!cutting) {
			throw new IOException(REASON);
		}

		channel.truncate(size);
		return this;
	}

	@Override
	public FileChannel position(long position) throws IOException {
		channel.position(position);
		return this;
	}

	@Override
	public long position() throws IOException {
		return channel.position();
	}

	@Override
	public long size() throws IOException {
		return channel.size();
	}

	@Override
	public void force(boolean metadata) throws IOException {
		channel.force(metadata);
	}

	@Override
	protected void implCloseChannel() throws IOException {
		channel.close();
	}

	@Override
	public int write(ByteBuffer source, long position) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long write(ByteBuffer[] sources, int offset, int length) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long transferFrom(ReadableByteChannel source, long position, long count) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long transferTo(long position, long count, WritableByteChannel target) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int read(ByteBuffer target) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int read(ByteBuffer target, long position) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long read(ByteBuffer[] targets, int offset, int length) {
		throw new UnsupportedOperationException();
	}

	@Override
	public MappedByteBuffer map(MapMode mode, long position, long size) {
		throw new UnsupportedOperationException();
	}

	@Override
	public FileLock lock(long position, long size, boolean shared) {
		throw new UnsupportedOperationException();
	}

	@Override
	public FileLock tryLock(long position, long size, boolean shared) {
		throw new UnsupportedOperationException();
	}
}
