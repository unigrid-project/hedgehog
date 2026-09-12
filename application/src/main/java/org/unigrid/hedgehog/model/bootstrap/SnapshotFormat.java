/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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
import java.nio.charset.StandardCharsets;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/*
   Layout of the converted bootstrap, big-endian throughout to match the rest of the codebase:

   header          128 bytes, the field offsets below
   address table   40 bytes per address, sorted by hash160 so a balance is one binary search
   block times     4 bytes per height, so an entry only has to carry a height to have a date
   entry table     20 bytes per credit or debit, grouped per address and ordered by height
   transaction ids 32 bytes each, referenced by index from the entries
   signature       optional, appended after the content: magic, length, the DER signature
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotFormat {
	public static final byte[] MAGIC = "UGDSNAP1".getBytes(StandardCharsets.US_ASCII);
	public static final int VERSION = 2;

	public static final int HEADER_SIZE = 128;
	public static final int ADDRESS_RECORD_SIZE = 40;
	public static final int ENTRY_RECORD_SIZE = 20;
	public static final int BLOCK_TIME_RECORD_SIZE = 4;

	public static final int VERSION_OFFSET = 8;
	public static final int TIP_HASH_OFFSET = 16;
	public static final int TIP_HEIGHT_OFFSET = 48;
	public static final int ADDRESS_COUNT_OFFSET = 52;
	public static final int ENTRY_COUNT_OFFSET = 56;
	public static final int TRANSACTION_COUNT_OFFSET = 64;
	public static final int BUILT_AT_OFFSET = 72;
	public static final int ADDRESS_TABLE_OFFSET = 80;
	public static final int BLOCK_TIME_TABLE_OFFSET = 88;
	public static final int ENTRY_TABLE_OFFSET = 96;
	public static final int TRANSACTION_TABLE_OFFSET = 104;
	public static final int TOTAL_UNSPENT_OFFSET = 112;
	public static final int ZEROCOIN_MINTED_OFFSET = 120;

	public static final int ADDRESS_BALANCE_OFFSET = 20;
	public static final int ADDRESS_FIRST_ENTRY_OFFSET = 28;
	public static final int ADDRESS_ENTRY_COUNT_OFFSET = 32;

	public static final int ENTRY_AMOUNT_OFFSET = 0;
	public static final int ENTRY_HEIGHT_OFFSET = 8;
	public static final int ENTRY_TRANSACTION_OFFSET = 12;
	public static final int ENTRY_KIND_OFFSET = 16;

	public static final byte[] SIGNATURE_MAGIC = "UGDSIGN1".getBytes(StandardCharsets.US_ASCII);
	public static final int SIGNATURE_HEADER_SIZE = 12;
	public static final int SIGNATURE_LENGTH_OFFSET = 8;
	public static final int MAXIMUM_SIGNATURE_SIZE = 256;

	public static long contentLength(ByteBuffer header) {
		return header.getLong(TRANSACTION_TABLE_OFFSET)
			+ header.getLong(TRANSACTION_COUNT_OFFSET) * Hashing.HASH_SIZE;
	}
}
