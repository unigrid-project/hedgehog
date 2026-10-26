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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/* Copies what passes through into the cache. A failing or oversized copy is given up on, never the read itself,
   so a full cache disk cannot fail a store. */
final class TeeInputStream extends FilterInputStream {
	private final OutputStream copy;
	private final long limit;
	private long copied;
	private boolean exhausted;
	private boolean abandoned;

	TeeInputStream(final InputStream input, final OutputStream copy, final long limit) {
		super(input);
		this.copy = copy;
		this.limit = limit;
	}

	boolean isComplete() {
		return exhausted && !abandoned;
	}

	@Override
	public int read() throws IOException {
		final byte[] single = new byte[1];
		return read(single, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(single[0]);
	}

	@Override
	public int read(final byte[] bytes, final int offset, final int length) throws IOException {
		final int count = super.read(bytes, offset, length);

		exhausted |= count < 0;
		copy(bytes, offset, Math.max(0, count));
		return count;
	}

	/* Skipped or repeated bytes would leave a copy that no longer matches what was stored */
	@Override
	public long skip(final long count) throws IOException {
		abandoned = true;
		return super.skip(count);
	}

	@Override
	public boolean markSupported() {
		return false;
	}

	@Override
	public void reset() throws IOException {
		abandoned = true;
		super.reset();
	}

	private void copy(final byte[] bytes, final int offset, final int count) {
		copied += count;
		abandoned |= copied > limit;

		if (!abandoned) {
			try {
				copy.write(bytes, offset, count);
			} catch (IOException ex) {
				abandoned = true;
			}
		}
	}
}
