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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HexFormat;

final class Bytes {
	private Bytes() {
		/* Static helpers only */
	}

	static byte[] requireSize(byte[] bytes, int size, String name) {
		if (bytes.length != size) {
			throw new IllegalArgumentException(name + " is exactly " + size + " bytes, found " + bytes.length);
		}

		return bytes.clone();
	}

	static byte[] concat(byte[]... parts) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		for (final byte[] part : parts) {
			out.writeBytes(part);
		}

		return out.toByteArray();
	}

	static byte[] intBytes(int value) {
		return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
	}

	static byte[] longBytes(long value) {
		return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
	}

	static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	/* A buffer that is too short is malformed input, so it surfaces as an IllegalArgumentException
	   rather than as the BufferUnderflowException a bare get would throw. */
	static byte[] take(ByteBuffer in, int size) {
		requireRemaining(in, size);

		final byte[] bytes = new byte[size];

		in.get(bytes);
		return bytes;
	}

	static long takeLong(ByteBuffer in) {
		requireRemaining(in, Long.BYTES);
		return in.getLong();
	}

	static int takeUnsignedShort(ByteBuffer in) {
		requireRemaining(in, Short.BYTES);
		return Short.toUnsignedInt(in.getShort());
	}

	private static void requireRemaining(ByteBuffer in, int size) {
		if (in.remaining() < size) {
			throw new IllegalArgumentException("Truncated: needs " + size + " bytes, found " + in.remaining());
		}
	}
}
