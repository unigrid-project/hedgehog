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
import java.util.Objects;

/* One validator's signature over a block hash; more than 2/3 of the set make the block's certificate */
public record Endorsement(AccountKey signer, byte[] signature) {
	public Endorsement {
		Objects.requireNonNull(signer);
		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "An endorsement signature");
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Endorsement endorsement && signer.equals(endorsement.signer)
			&& Arrays.equals(signature, endorsement.signature);
	}

	@Override
	public int hashCode() {
		return 31 * signer.hashCode() + Arrays.hashCode(signature);
	}
}
