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
import java.util.Arrays;

/* An opaque id that a mint carries and the ledger lets be used once. What it stands for is not the
   ledger's business, which is what keeps the ledger free of any knowledge of where a mint comes from. */
public record Reference(byte[] bytes) implements Comparable<Reference> {
	public static final int SIZE = 32;

	private static final byte[] CLAIM_TAG = "Unigrid claim v1".getBytes(StandardCharsets.US_ASCII);

	public Reference {
		bytes = Bytes.requireSize(bytes, SIZE, "A reference");
	}

	/* One reference for the snapshot balance of each legacy address, so it is taken from the snapshot once */
	public static Reference ofClaim(byte[] snapshotHash, LegacyAddress address) {
		return new Reference(Arrays.copyOf(Digests.sha512(CLAIM_TAG, snapshotHash, address.bytes()), SIZE));
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Reference reference && Arrays.equals(bytes, reference.bytes);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(bytes);
	}

	@Override
	public int compareTo(Reference other) {
		return Arrays.compareUnsigned(bytes, other.bytes);
	}

	@Override
	public String toString() {
		return Bytes.hex(bytes);
	}
}
