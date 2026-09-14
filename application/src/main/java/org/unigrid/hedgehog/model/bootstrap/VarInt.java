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

import java.nio.ByteBuffer;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VarInt {
	private static final int TWO_BYTE_MARKER = 0xfd;
	private static final int FOUR_BYTE_MARKER = 0xfe;
	private static final int EIGHT_BYTE_MARKER = 0xff;

	public static long read(ByteBuffer buffer) {
		final int marker = Byte.toUnsignedInt(buffer.get());

		switch (marker) {
			case TWO_BYTE_MARKER:
				return Short.toUnsignedInt(buffer.getShort());
			case FOUR_BYTE_MARKER:
				return Integer.toUnsignedLong(buffer.getInt());
			case EIGHT_BYTE_MARKER:
				return buffer.getLong();
			default:
				return marker;
		}
	}

	public static int readCount(ByteBuffer buffer) {
		final long value = read(buffer);

		if (value < 0 || value > Integer.MAX_VALUE) {
			throw new IllegalStateException("Count out of range: " + value);
		}

		return (int) value;
	}

	public static void write(ByteBuffer buffer, long value) {
		if (value < TWO_BYTE_MARKER) {
			buffer.put((byte) value);
		} else if (value <= 0xffffL) {
			buffer.put((byte) TWO_BYTE_MARKER).putShort((short) value);
		} else if (value <= 0xffffffffL) {
			buffer.put((byte) FOUR_BYTE_MARKER).putInt((int) value);
		} else {
			buffer.put((byte) EIGHT_BYTE_MARKER).putLong(value);
		}
	}
}
