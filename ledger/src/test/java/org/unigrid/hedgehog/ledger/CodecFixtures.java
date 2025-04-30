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

import java.util.Random;

/* Structurally valid values made of random bytes; their signatures do not verify */
final class CodecFixtures {
	private CodecFixtures() {
		/* Static helpers only */
	}

	static byte[] random(Random random, int size) {
		final byte[] bytes = new byte[size];

		random.nextBytes(bytes);
		return bytes;
	}

	static AccountKey account(Random random) {
		return new AccountKey(random(random, AccountKey.SIZE));
	}

	static Mint mint(Random random) {
		return new Mint(account(random), 1 + random.nextInt(1_000_000), new Reference(random(random, Reference.SIZE)));
	}

	static Vote vote(Random random) {
		final Vote.Action action = random.nextBoolean() ? Vote.Action.ADD : Vote.Action.REMOVE;

		return new Vote(account(random), account(random), action, random.nextInt(1000),
			random(random, Ed25519.SIGNATURE_SIZE));
	}

	static Transaction transaction(Random random) {
		return random.nextBoolean() ? mint(random) : vote(random);
	}
}
