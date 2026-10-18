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

package org.unigrid.hedgehog.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.unigrid.hedgehog.model.s3.entity.UploadTooLargeException;

public class LimitedInputStream extends FilterInputStream {
	private final long maxBytes;
	private long count;

	public LimitedInputStream(InputStream stream, long maxBytes) {
		super(stream);
		this.maxBytes = maxBytes;
	}

	private int counted(int bytes) throws UploadTooLargeException {
		if (bytes > 0) {
			count += bytes;
		}

		if (count > maxBytes) {
			throw new UploadTooLargeException(maxBytes);
		}

		return bytes;
	}

	@Override
	public int read() throws IOException {
		final int value = super.read();

		counted(value == -1 ? 0 : 1);
		return value;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		return counted(super.read(buffer, offset, length));
	}
}
