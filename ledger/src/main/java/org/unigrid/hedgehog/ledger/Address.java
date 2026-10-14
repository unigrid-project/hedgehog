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

import java.nio.ByteBuffer;
import java.util.Arrays;

/* Who can hold a balance: an Ed25519 key, which can also sign, or the hash of a key of the legacy chain, which can
   only be paid and whose owner later claims what it holds. The type byte keeps the two apart wherever an address is
   written down. */
public sealed interface Address extends Comparable<Address> permits AccountKey, LegacyAddress {
	byte ED25519 = 1;
	byte LEGACY = 2;

	byte type();

	byte[] bytes();

	default byte[] encode() {
		return Bytes.concat(new byte[] { type() }, bytes());
	}

	/* Each type has a fixed width, so a decoder never trusts a length it read from the wire */
	static Address decode(ByteBuffer in) {
		final byte type = Bytes.take(in, 1)[0];

		return switch (type) {
			case ED25519 -> new AccountKey(Bytes.take(in, AccountKey.SIZE));
			case LEGACY -> new LegacyAddress(Bytes.take(in, LegacyAddress.SIZE));
			default -> throw new IllegalArgumentException("Unknown address type " + Byte.toUnsignedInt(type));
		};
	}

	@Override
	default int compareTo(Address other) {
		final int byType = Byte.compare(type(), other.type());

		return byType != 0 ? byType : Arrays.compareUnsigned(bytes(), other.bytes());
	}
}
