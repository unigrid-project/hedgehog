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

public final class TransactionCodec {
	private TransactionCodec() {
		/* Static helpers only */
	}

	/* Every field has a fixed width, so a decoder never trusts a length it read from the wire */
	public static Transaction decode(ByteBuffer in) {
		final byte kind = Bytes.take(in, 1)[0];

		return switch (kind) {
			case Mint.KIND -> decodeMint(in);
			case Vote.KIND -> decodeVote(in);
			default -> throw new IllegalArgumentException("Unknown transaction kind " + kind);
		};
	}

	private static Mint decodeMint(ByteBuffer in) {
		return new Mint(new AccountKey(Bytes.take(in, AccountKey.SIZE)), Bytes.takeLong(in),
			new Reference(Bytes.take(in, Reference.SIZE)));
	}

	private static Vote decodeVote(ByteBuffer in) {
		final AccountKey voter = new AccountKey(Bytes.take(in, AccountKey.SIZE));
		final AccountKey candidate = new AccountKey(Bytes.take(in, AccountKey.SIZE));
		final Vote.Action action = Vote.Action.of(Bytes.take(in, 1)[0]);
		final long round = Bytes.takeLong(in);

		return new Vote(voter, candidate, action, round, Bytes.take(in, Ed25519.SIGNATURE_SIZE));
	}
}
