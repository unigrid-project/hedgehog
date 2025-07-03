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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class LedgerFixtures {
	static final MintAuthority ANY_MINT = mint -> true;
	static final MintAuthority NO_MINT = mint -> false;

	private LedgerFixtures() {
		/* Static helpers only */
	}

	static byte[] seed(int n) {
		return ByteBuffer.allocate(Ed25519.SEED_SIZE).putInt(n).array();
	}

	static AccountKey key(int n) {
		return Ed25519.publicKey(seed(n));
	}

	/* Accounts that hold balances, kept apart from the validator keys of the same small numbers */
	static AccountKey account(int n) {
		return key(1000 + n);
	}

	static Reference reference(int n) {
		return new Reference(ByteBuffer.allocate(Reference.SIZE).putInt(n).array());
	}

	static Mint mint(int n, long amount) {
		return new Mint(account(n), amount, reference(n));
	}

	static List<byte[]> seeds(int from, int count) {
		final List<byte[]> seeds = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			seeds.add(seed(from + i));
		}

		return seeds;
	}

	static LedgerGenesis genesis(int roundLength, List<byte[]> validatorSeeds) {
		return new LedgerGenesis(1000, roundLength,
			new ValidatorSet(validatorSeeds.stream().map(Ed25519::publicKey).toList()));
	}

	static LedgerState state(int roundLength, MintAuthority authority, List<byte[]> validatorSeeds) {
		return new LedgerState(genesis(roundLength, validatorSeeds), authority);
	}

	/* A single foundation validator, which is quorum on its own */
	static LedgerState soloState(MintAuthority authority) {
		return state(1000, authority, seeds(1, 1));
	}

	private static byte[] seedOf(AccountKey key, List<byte[]> seeds) {
		return seeds.stream().filter(seed -> Ed25519.publicKey(seed).equals(key)).findFirst().orElseThrow();
	}

	/* The block the state expects next: proposed by whoever is due, with the correct roots, endorsed by the
	   proposer first and then the other validators in set order, until there are `endorsers` of them. */
	static Block block(LedgerState state, List<byte[]> seeds, int endorsers, long time,
		List<Transaction> transactions) {
		final long height = state.height() + 1;
		final ValidatorSet set = state.validators();
		final AccountKey proposer = set.proposerAt(height);
		Block block = Block.builder().height(height).previousHash(state.tipHash()).time(time)
			.stateRoot(state.rootAfter(height, transactions)).transactionRoot(Block.transactionRootOf(transactions))
			.transactions(transactions).proposer(proposer).endorsements(List.of()).build();
		int endorsed = 0;

		block = block.endorsedBy(seedOf(proposer, seeds));
		endorsed++;

		for (final AccountKey key : set.keys()) {
			if (endorsed >= endorsers) {
				break;
			}

			if (!key.equals(proposer)) {
				block = block.endorsedBy(seedOf(key, seeds));
				endorsed++;
			}
		}

		return block;
	}

	/* Endorsed by every validator in the set */
	static Block block(LedgerState state, List<byte[]> seeds, List<Transaction> transactions) {
		return block(state, seeds, state.validators().size(), state.tipTime(), transactions);
	}

	static Block soloBlock(LedgerState state, Transaction... transactions) {
		return block(state, seeds(1, 1), Arrays.asList(transactions));
	}
}
