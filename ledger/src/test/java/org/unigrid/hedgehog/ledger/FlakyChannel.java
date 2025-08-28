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

package org.unigrid.hedgehog.ledger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/* A real channel that can be told to write only part of the next buffer and then fail, as a full disk or a
   dying one does */
final class FlakyChannel extends FileChannel {
	private final FileChannel real;
	private int failAfter = -1;

	FlakyChannel(FileChannel real) {
		this.real = real;
	}

	void failNextWriteAfter(int bytes) {
		failAfter = bytes;
	}

	@Override
	public int write(ByteBuffer source) throws IOException {
		if (failAfter < 0) {
			return real.write(source);
		}

		final ByteBuffer part = source.slice();

		part.limit(Math.min(failAfter, part.remaining()));
		real.write(part);
		failAfter = -1;
		throw new IOException("The disk gave out in the middle of a write");
	}

	@Override
	public int read(ByteBuffer destination) throws IOException {
		return real.read(destination);
	}

	@Override
	public long read(ByteBuffer[] destinations, int offset, int length) throws IOException {
		return real.read(destinations, offset, length);
	}

	@Override
	public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
		return real.write(sources, offset, length);
	}

	@Override
	public long position() throws IOException {
		return real.position();
	}

	@Override
	public FileChannel position(long newPosition) throws IOException {
		real.position(newPosition);
		return this;
	}

	@Override
	public long size() throws IOException {
		return real.size();
	}

	@Override
	public FileChannel truncate(long size) throws IOException {
		real.truncate(size);
		return this;
	}

	@Override
	public void force(boolean metaData) throws IOException {
		real.force(metaData);
	}

	@Override
	public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
		return real.transferTo(position, count, target);
	}

	@Override
	public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException {
		return real.transferFrom(source, position, count);
	}

	@Override
	public int read(ByteBuffer destination, long position) throws IOException {
		return real.read(destination, position);
	}

	@Override
	public int write(ByteBuffer source, long position) throws IOException {
		return real.write(source, position);
	}

	@Override
	public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
		return real.map(mode, position, size);
	}

	@Override
	public FileLock lock(long position, long size, boolean shared) throws IOException {
		return real.lock(position, size, shared);
	}

	@Override
	public FileLock tryLock(long position, long size, boolean shared) throws IOException {
		return real.tryLock(position, size, shared);
	}

	@Override
	protected void implCloseChannel() throws IOException {
		real.close();
	}
}
