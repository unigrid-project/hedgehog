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

package org.unigrid.hedgehog.s3.index;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/* A key with its value, or without one a tombstone that hides every older value of the key */
record Slot(byte[] key, byte[] value) {
	boolean tombstone() {
		return Objects.isNull(value);
	}

	@Override
	public boolean equals(final Object other) {
		return other instanceof Slot slot && Arrays.equals(key, slot.key) && Arrays.equals(value, slot.value);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(key) * 31 + Arrays.hashCode(value);
	}

	@Override
	public String toString() {
		final String shown = tombstone() ? "tombstone" : HexFormat.of().formatHex(value);
		return HexFormat.of().formatHex(key) + "=" + shown;
	}
}
