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

import java.nio.charset.StandardCharsets;

/* What a validator signs to commit a block: the chain, the height, the view of the height and the block hash. A
   signature made in one view, at one height or on one chain is worth nothing in another. */
public final class Precommit {
	private static final byte[] TAG = "Unigrid precommit v1".getBytes(StandardCharsets.US_ASCII);

	private Precommit() {
		/* Static helpers only */
	}

	public static byte[] sign(byte[] validatorSeed, byte[] chainId, long height, int view, byte[] blockHash) {
		return Ed25519.sign(validatorSeed, payload(chainId, height, view, blockHash));
	}

	public static boolean isSigned(Endorsement endorsement, byte[] chainId, long height, int view,
		byte[] blockHash) {
		return Ed25519.verify(endorsement.signer(), payload(chainId, height, view, blockHash),
			endorsement.signature());
	}

	private static byte[] payload(byte[] chainId, long height, int view, byte[] blockHash) {
		return Digests.sha512(TAG, chainId, Bytes.longBytes(height), Bytes.intBytes(view), blockHash);
	}
}
