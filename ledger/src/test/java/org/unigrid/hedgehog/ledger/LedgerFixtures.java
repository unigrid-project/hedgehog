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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class LedgerFixtures {
	static final LedgerAuthority ANY_MINT = authority(true, true);
	static final LedgerAuthority NO_MINT = authority(false, true);
	static final byte[] SNAPSHOT_HASH = Digests.sha512("snapshot".getBytes(StandardCharsets.US_ASCII));

	private LedgerFixtures() {
		/* Static helpers only */
	}

	static LedgerAuthority authority(boolean mints, boolean claims) {
		return new LedgerAuthority() {
			@Override
			public boolean authorizes(Mint mint) {
				return mints;
			}

			@Override
			public boolean authorizes(Claim claim) {
				return claims;
			}
		};
	}

	static LedgerAuthority mintsWhere(Predicate<Mint> allowed) {
		return new LedgerAuthority() {
			@Override
			public boolean authorizes(Mint mint) {
				return allowed.test(mint);
			}

			@Override
			public boolean authorizes(Claim claim) {
				return true;
			}
		};
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

	static byte[] accountSeed(int n) {
		return seed(1000 + n);
	}

	static Transfer transfer(LedgerState state, int from, Address to, long amount, long fee, long nonce) {
		return Transfer.signed(accountSeed(from), to, amount, fee, nonce, state.chainId());
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

	static LedgerState state(int roundLength, LedgerAuthority authority, List<byte[]> validatorSeeds) {
		return new LedgerState(genesis(roundLength, validatorSeeds), authority);
	}

	/* A single foundation validator, which is quorum on its own */
	static LedgerState soloState(LedgerAuthority authority) {
		return state(1000, authority, seeds(1, 1));
	}

	static LedgerState stateWithSnapshot(LedgerAuthority authority, List<byte[]> validatorSeeds, long total,
		Map<LegacyAddress, Long> balances) {
		final ValidatorSet validators = new ValidatorSet(validatorSeeds.stream().map(Ed25519::publicKey).toList());

		return new LedgerState(new LedgerGenesis(1000, 1000, validators, SNAPSHOT_HASH, total), authority,
			address -> balances.getOrDefault(address, 0L));
	}

	static LedgerState soloSnapshotState(LedgerAuthority authority, Map<LegacyAddress, Long> balances) {
		return stateWithSnapshot(authority, seeds(1, 1),
			balances.values().stream().mapToLong(Long::longValue).sum(), balances);
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
			.transactions(transactions).proposer(proposer).endorsements(List.of())
			.lastCommit(state.tipCertificate()).build();
		int endorsed = 0;

		block = block.endorsedBy(seedOf(proposer, seeds), state.chainId());
		endorsed++;

		for (final AccountKey key : set.keys()) {
			if (endorsed >= endorsers) {
				break;
			}

			if (!key.equals(proposer)) {
				block = block.endorsedBy(seedOf(key, seeds), state.chainId());
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

	/* A block with the transactions the state would not take, signed by the solo validator as a certified one */
	static Block withTransactions(LedgerState state, Block sound, Transaction... transactions) {
		final List<Transaction> replaced = List.of(transactions);

		return sound.toBuilder().transactions(replaced).transactionRoot(Block.transactionRootOf(replaced))
			.endorsements(List.of()).build().endorsedBy(seed(1), state.chainId());
	}
}
