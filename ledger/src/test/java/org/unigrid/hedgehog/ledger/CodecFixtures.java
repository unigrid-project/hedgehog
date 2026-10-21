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

import java.util.Random;
import java.util.stream.IntStream;

/* Structurally valid values made of random bytes; their signatures do not verify */
final class CodecFixtures {
	static final int LAST_COMMIT_ENDORSEMENTS = 2;

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

	static LegacyAddress legacyAddress(Random random) {
		return new LegacyAddress(random(random, LegacyAddress.SIZE));
	}

	static Mint mint(Random random) {
		final Address recipient = random.nextBoolean() ? account(random) : legacyAddress(random);

		return new Mint(recipient, 1 + random.nextInt(1_000_000), new Reference(random(random, Reference.SIZE)));
	}

	static Vote vote(Random random) {
		final Vote.Action action = random.nextBoolean() ? Vote.Action.ADD : Vote.Action.REMOVE;

		return new Vote(account(random), account(random), action, random.nextInt(1000),
			random(random, Ed25519.SIGNATURE_SIZE));
	}

	static Claim claim(Random random, boolean compressed) {
		final byte[] key = random(random, compressed ? 33 : 65);

		key[0] = (byte) (compressed ? 2 + random.nextInt(2) : 4);
		return new Claim(key, account(random), random(random, LegacySignature.SIZE));
	}

	static Claim claim(Random random) {
		return claim(random, random.nextBoolean());
	}

	static Transfer transfer(Random random) {
		final Address recipient = random.nextBoolean() ? account(random) : legacyAddress(random);

		return new Transfer(account(random), recipient, 1 + random.nextInt(1_000_000), random.nextInt(1000),
			random.nextInt(100), random(random, Ed25519.SIGNATURE_SIZE));
	}

	static Transaction transaction(Random random) {
		return switch (random.nextInt(4)) {
			case 0 -> mint(random);
			case 1 -> vote(random);
			case 2 -> claim(random);
			default -> transfer(random);
		};
	}

	static Block block(Random random, int transactions, int endorsements) {
		return Block.builder().height(random.nextInt(1000) + 1L).view(random.nextInt(5))
			.previousHash(random(random, Digests.HASH_SIZE)).time(random.nextInt(100000))
			.stateRoot(random(random, Digests.HASH_SIZE)).transactionRoot(random(random, Digests.HASH_SIZE))
			.transactions(IntStream.range(0, transactions).mapToObj(i -> transaction(random)).toList())
			.proposer(account(random))
			.certificate(new Certificate(random.nextInt(5), IntStream.range(0, endorsements)
				.mapToObj(i -> new Endorsement(account(random), random(random, Ed25519.SIGNATURE_SIZE))).toList()))
			.lastCommit(new Certificate(random.nextInt(5), IntStream.range(0, LAST_COMMIT_ENDORSEMENTS)
				.mapToObj(i -> new Endorsement(account(random), random(random, Ed25519.SIGNATURE_SIZE))).toList()))
			.build();
	}
}
