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

import java.nio.ByteBuffer;
import java.util.Objects;

/* Creates an amount in an account. Whether it may be created is not in the transaction but in the
   MintAuthority the ledger asks, and the reference makes sure each one is created only once. */
public record Mint(AccountKey recipient, long amount, Reference reference) implements Transaction {
	public static final byte KIND = 1;
	public static final int ENCODED_SIZE = 1 + AccountKey.SIZE + Long.BYTES + Reference.SIZE;

	public Mint {
		Objects.requireNonNull(recipient);
		Objects.requireNonNull(reference);

		if (amount <= 0) {
			throw new IllegalArgumentException("A mint is of an amount above 0, found " + amount);
		}
	}

	@Override
	public byte kind() {
		return KIND;
	}

	@Override
	public byte[] encode() {
		return ByteBuffer.allocate(ENCODED_SIZE).put(KIND).put(recipient.bytes()).putLong(amount)
			.put(reference.bytes()).array();
	}
}
