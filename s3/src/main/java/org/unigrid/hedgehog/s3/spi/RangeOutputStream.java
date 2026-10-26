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

package org.unigrid.hedgehog.s3.spi;

import java.io.IOException;
import java.io.OutputStream;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
final class RangeOutputStream extends OutputStream {
	private final OutputStream output;
	private final long start;
	private final long end;
	private long position;

	@Override
	public void write(final int value) throws IOException {
		if (position >= start && position < end) {
			output.write(value);
		}

		position++;
	}

	@Override
	public void write(final byte[] bytes, final int offset, final int length) throws IOException {
		final long from = Math.max(start, position);
		final long to = Math.min(end, position + length);

		if (from < to) {
			output.write(bytes, offset + (int) (from - position), (int) (to - from));
		}

		position += length;
	}
}
