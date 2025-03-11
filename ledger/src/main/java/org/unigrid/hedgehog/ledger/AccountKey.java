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

import java.util.Arrays;
import java.util.HexFormat;

public record AccountKey(byte[] bytes) implements Comparable<AccountKey> {
	public static final int SIZE = 32;

	public AccountKey {
		bytes = Bytes.requireSize(bytes, SIZE, "An account key");
	}

	public static AccountKey fromHex(String hex) {
		return new AccountKey(HexFormat.of().parseHex(hex));
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof AccountKey key && Arrays.equals(bytes, key.bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public int compareTo(AccountKey other) {
		return Arrays.compareUnsigned(bytes, other.bytes);
	}

	@Override
	public String toString() {
		return Bytes.hex(bytes);
	}
}
