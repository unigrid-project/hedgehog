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

package org.unigrid.hedgehog.proxy.chain;

import java.util.Arrays;
import java.util.HexFormat;

public abstract class FixedBytes {
	private final byte[] bytes;

	protected FixedBytes(byte[] bytes, int size) {
		if (bytes.length != size) {
			throw new IllegalArgumentException(getClass().getSimpleName() + " is exactly " + size
				+ " bytes, found " + bytes.length);
		}

		this.bytes = bytes.clone();
	}

	public byte[] bytes() {
		return bytes.clone();
	}

	protected int compareBytes(FixedBytes other) {
		return Arrays.compareUnsigned(bytes, other.bytes);
	}

	@Override
	public boolean equals(Object other) {
		return other != null && getClass() == other.getClass() && Arrays.equals(bytes, ((FixedBytes) other).bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public String toString() {
		return HexFormat.of().formatHex(bytes);
	}
}
