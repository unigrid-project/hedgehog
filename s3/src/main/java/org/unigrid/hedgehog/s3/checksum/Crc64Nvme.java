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

package org.unigrid.hedgehog.s3.checksum;

import java.util.zip.Checksum;

/* CRC-64/NVME, the 64-bit checksum S3 offers next to CRC32 and CRC32C, which the JDK lacks */
public final class Crc64Nvme implements Checksum {
	/* The polynomial 0xAD93D23594C93659 bit-reversed, since the CRC consumes each byte least significant bit first */
	private static final long POLYNOMIAL = 0x9A6C9329AC4BC9B5L;
	private static final long[] TABLE = table();

	private long crc = -1L;

	private static long[] table() {
		final long[] table = new long[1 << Byte.SIZE];

		for (int value = 0; value < table.length; value++) {
			long crc = value;

			for (int bit = 0; bit < Byte.SIZE; bit++) {
				crc = (crc & 1) == 0 ? crc >>> 1 : (crc >>> 1) ^ POLYNOMIAL;
			}

			table[value] = crc;
		}

		return table;
	}

	@Override
	public void update(final int value) {
		crc = TABLE[(int) (crc ^ value) & 0xFF] ^ (crc >>> Byte.SIZE);
	}

	@Override
	public void update(final byte[] bytes, final int offset, final int length) {
		for (int i = offset; i < offset + length; i++) {
			update(bytes[i]);
		}
	}

	@Override
	public long getValue() {
		return ~crc;
	}

	@Override
	public void reset() {
		crc = -1L;
	}
}
