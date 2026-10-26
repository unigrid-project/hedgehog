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

package org.unigrid.hedgehog.s3.cache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/* Holds a cached file in place, out of reach of eviction, until closed */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class CacheLease implements AutoCloseable {
	private final CachingBlobStore store;
	private final CacheEntry entry;
	private boolean closed;

	public long size() {
		return entry.getSize();
	}

	/* For sending the file without copying it. The channel is shared with every other reader of the file, so it is
	   read by position only and left open. */
	public FileChannel channel() {
		return entry.getFile().getChannel();
	}

	public void writeTo(final OutputStream output, final long offset, final long length) throws IOException {
		Objects.checkFromIndexSize(offset, length, size());
		entry.getFile().writeTo(output, offset, length);
	}

	@Override
	public void close() throws IOException {
		if (!closed) {
			closed = true;
			store.release(entry);
		}
	}
}
