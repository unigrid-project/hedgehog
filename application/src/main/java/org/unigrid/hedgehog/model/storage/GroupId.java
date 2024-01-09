/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.storage;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;

public final class GroupId implements Serializable {
	public static final int SIZE = 32;

	private final byte[] value;

	private GroupId(byte[] value) {
		this.value = value;
	}

	public static GroupId of(byte[] value) {
		if (value.length != SIZE) {
			throw new IllegalArgumentException("A group id is exactly " + SIZE + " bytes");
		}

		return new GroupId(value.clone());
	}

	public static GroupId fromHex(String hex) {
		return of(HexFormat.of().parseHex(hex));
	}

	public byte[] bytes() {
		return value.clone();
	}

	public String toHex() {
		return HexFormat.of().formatHex(value);
	}

	public int prefix() {
		return ByteBuffer.wrap(value).getInt();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof GroupId && Arrays.equals(value, ((GroupId) other).value);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(value);
	}

	@Override
	public String toString() {
		return "GroupId[" + toHex() + "]";
	}
}
