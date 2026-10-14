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

import java.util.Arrays;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PrecommitTest {
	private static byte[] filled(int fill) {
		final byte[] bytes = new byte[Digests.HASH_SIZE];

		Arrays.fill(bytes, (byte) fill);
		return bytes;
	}

	private static byte[] seed(int first) {
		final byte[] seed = new byte[Ed25519.SEED_SIZE];

		seed[0] = (byte) first;
		return seed;
	}

	private static Endorsement endorsement() {
		return new Endorsement(Ed25519.publicKey(seed(1)), Precommit.sign(seed(1), filled(1), 5, 2, filled(9)));
	}

	@Example
	public void shouldVerifyWhatItSigned() {
		assertThat(Precommit.isSigned(endorsement(), filled(1), 5, 2, filled(9)), is(true));
	}

	/* A signature made in one view, at one height, on one chain or for one block is worth nothing for another */
	@Example
	public void shouldBindEverythingItSigns() {
		assertThat(Precommit.isSigned(endorsement(), filled(2), 5, 2, filled(9)), is(false));
		assertThat(Precommit.isSigned(endorsement(), filled(1), 6, 2, filled(9)), is(false));
		assertThat(Precommit.isSigned(endorsement(), filled(1), 5, 3, filled(9)), is(false));
		assertThat(Precommit.isSigned(endorsement(), filled(1), 5, 2, filled(8)), is(false));
	}
}
