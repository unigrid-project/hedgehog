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
import java.util.ArrayList;
import java.util.List;

/* The signatures that commit a block and the view of its height in which they were made. More than 2/3 of the
   validators make one valid. */
public record Certificate(int view, List<Endorsement> endorsements) {
	public static final Certificate EMPTY = new Certificate(0, List.of());
	public static final int ENDORSEMENT_SIZE = AccountKey.SIZE + Ed25519.SIGNATURE_SIZE;
	public static final int MAX_ENCODED_SIZE = Integer.BYTES + Short.BYTES + ValidatorSet.MAX_SIZE * ENDORSEMENT_SIZE;

	public Certificate {
		if (view < 0) {
			throw new IllegalArgumentException("A view is 0 or more, found " + view);
		}

		endorsements = List.copyOf(endorsements);
	}

	/* A count above the maximum is refused here as it is on decoding, so that nothing is written that cannot be
	   read back */
	public byte[] encode() {
		if (endorsements.size() > ValidatorSet.MAX_SIZE) {
			throw new IllegalArgumentException("A certificate holds at most " + ValidatorSet.MAX_SIZE
				+ " endorsements, found " + endorsements.size());
		}

		final ByteBuffer out = ByteBuffer.allocate(Integer.BYTES + Short.BYTES
			+ endorsements.size() * ENDORSEMENT_SIZE);

		out.putInt(view).putShort((short) endorsements.size());
		endorsements.forEach(endorsement -> out.put(endorsement.signer().bytes()).put(endorsement.signature()));
		return out.array();
	}

	/* The list grows one decoded entry at a time and is never sized from a count read off the wire */
	public static Certificate decode(ByteBuffer in) {
		final int view = Bytes.takeInt(in);
		final int count = Bytes.takeUnsignedShort(in);

		if (count > ValidatorSet.MAX_SIZE) {
			throw new IllegalArgumentException("Too many endorsements: " + count);
		}

		final List<Endorsement> endorsements = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			endorsements.add(new Endorsement(new AccountKey(Bytes.take(in, AccountKey.SIZE)),
				Bytes.take(in, Ed25519.SIGNATURE_SIZE)));
		}

		return new Certificate(view, endorsements);
	}

	public byte[] hash() {
		return Digests.sha512(encode());
	}
}
