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

package org.unigrid.hedgehog.proxy.chain;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.zip.Checksum;

/* The FNVJ64 hash that greenrobot essentials dropped in commit 278bfa06: the FNV-1a offset basis and
   prime, but each round multiplies first and then mixes in eight big-endian bytes at once, and the
   length goes in last. It is fast and not collision resistant, so a signature never covers it alone. */
public final class FnvJ64 implements Checksum {
	private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
	private static final long PRIME = 0x100000001b3L;
	private static final VarHandle BIG_ENDIAN_LONG = MethodHandles.byteArrayViewVarHandle(long[].class,
		ByteOrder.BIG_ENDIAN);

	private long state = OFFSET_BASIS;
	private int partialBytes;
	private int length;

	public static long hash(byte[] bytes) {
		final FnvJ64 checksum = new FnvJ64();

		checksum.update(bytes, 0, bytes.length);
		return checksum.getValue();
	}

	@Override
	public void update(int b) {
		if (partialBytes == 0) {
			state *= PRIME;
		}

		state ^= (b & 0xffL) << (Long.SIZE - Byte.SIZE * (partialBytes + 1));
		partialBytes = (partialBytes + 1) % Long.BYTES;
		length++;
	}

	@Override
	public void update(byte[] b, int off, int len) {
		final int leading = Math.min(len, (Long.BYTES - partialBytes) % Long.BYTES);
		final int end = off + len;
		final int wordsEnd = end - (len - leading) % Long.BYTES;

		for (int i = off; i < off + leading; i++) {
			update(b[i]);
		}

		for (int i = off + leading; i < wordsEnd; i += Long.BYTES) {
			state = (state * PRIME) ^ (long) BIG_ENDIAN_LONG.get(b, i);
		}

		length += wordsEnd - off - leading;

		for (int i = wordsEnd; i < end; i++) {
			update(b[i]);
		}
	}

	@Override
	public long getValue() {
		return ((state * PRIME) ^ length) * PRIME;
	}

	@Override
	public void reset() {
		state = OFFSET_BASIS;
		partialBytes = 0;
		length = 0;
	}
}
