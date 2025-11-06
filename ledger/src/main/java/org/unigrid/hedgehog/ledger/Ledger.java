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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Ledger implements LedgerApplication {
	private final LedgerState state;
	private final BlockLog log;
	private final Mempool mempool = new Mempool();
	private final Map<AccountKey, Promise> promises = new HashMap<>();

	/* The log is replayed into the state, block by block and with every check, so a log that was tampered
	   with does not come up as a state */
	public Ledger(LedgerState state, BlockLog log) {
		this.state = state;
		this.log = log;
		log.blocks().forEach(state::apply);
	}

	@Override
	public synchronized Optional<String> submit(Transaction transaction) {
		if (mempool.admit(transaction, state)) {
			return Optional.empty();
		}

		return Optional.of(state.rejectionOf(transaction).orElse("Already waiting for a block"));
	}

	@Override
	public synchronized Optional<String> submit(Heartbeat heartbeat) {
		return mempool.admit(heartbeat, state) ? Optional.empty()
			: Optional.of("Not a newer heartbeat for this tip from a validator");
	}

	@Override
	public synchronized Optional<Block> propose(byte[] proposerSeed, long time) {
		final long height = state.height() + 1;
		final AccountKey proposer = Ed25519.publicKey(proposerSeed);

		if (!state.validators().proposerAt(height).equals(proposer)) {
			return Optional.empty();
		}

		final Promise earlier = promises.get(proposer);

		if (earlier != null && earlier.height() == height) {
			return Optional.ofNullable(earlier.proposal());
		}

		final List<Transaction> transactions = state.applicable(mempool.take(Block.MAX_TRANSACTIONS));

		if (transactions.isEmpty()) {
			return Optional.empty();
		}

		final Block block = Block.builder().height(height).previousHash(state.tipHash())
			.time(Math.max(time, state.tipTime())).stateRoot(state.rootAfter(height, transactions))
			.transactionRoot(Block.transactionRootOf(transactions)).transactions(transactions)
			.proposer(Ed25519.publicKey(proposerSeed)).endorsements(List.of()).build();

		final Block proposal = block.endorsedBy(proposerSeed);

		promises.put(proposer, new Promise(height, block.hash(), proposal));
		return Optional.of(proposal);
	}

	/* A signature promises that the block is right and that this validator signed no other block at its
	   height, so both are checked first. The promise is kept in memory only: a restarted node must be kept
	   from signing again at a height it signed before, which is for the key handling around the engine. */
	@Override
	public synchronized Block sign(Block block, byte[] validatorSeed) {
		final AccountKey signer = Ed25519.publicKey(validatorSeed);

		if (!state.validators().contains(signer)) {
			throw new IllegalArgumentException("Key " + signer + " is not a validator");
		}

		state.proposalRejection(block).ifPresent(reason -> {
			throw new InvalidBlockException(reason);
		});
		promiseNoOtherBlock(signer, block);
		return block.endorsedBy(validatorSeed);
	}

	private void promiseNoOtherBlock(AccountKey signer, Block block) {
		final Promise earlier = promises.get(signer);

		if (earlier != null && earlier.height() == block.getHeight()
			&& !Arrays.equals(earlier.hash(), block.hash())) {
			throw new IllegalStateException("Validator " + signer + " has signed another block at height "
				+ block.getHeight());
		}

		if (earlier == null || earlier.height() != block.getHeight()) {
			promises.put(signer, new Promise(block.getHeight(), block.hash(), null));
		}
	}

	/* What a validator has put its name to at a height. A proposer keeps the block it proposed, to hand it
	   back if asked again, since a second, different block at that height would be a double signature. */
	private record Promise(long height, byte[] hash, Block proposal) {
	}

	@Override
	public synchronized Optional<String> validate(Block block) {
		return state.rejectionOf(block);
	}

	/* The block is logged before the state changes, so a crash between the two replays to the same state and a
	   failed write leaves the state where it was */
	@Override
	public synchronized void commit(Block block) throws IOException {
		final Optional<String> rejection = state.rejectionOf(block);

		if (rejection.isPresent()) {
			throw new InvalidBlockException(rejection.get());
		}

		log.append(block);
		state.apply(block);
		mempool.removeCommitted(block, state);
	}

	@Override
	public synchronized ValidatorSet validators() {
		return state.validators();
	}

	@Override
	public synchronized long height() {
		return state.height();
	}

	@Override
	public synchronized byte[] tipHash() {
		return state.tipHash();
	}

	@Override
	public synchronized boolean isProgressing(long now, long window) {
		return mempool.isProgressing(state, now, window);
	}

	/* What votes and heartbeats are signed for */
	public synchronized byte[] chainId() {
		return state.chainId();
	}

	public synchronized long round() {
		return state.round();
	}

	public synchronized long balanceOf(AccountKey key) {
		return state.balanceOf(key);
	}

	public synchronized byte[] stateRoot() {
		return state.stateRoot();
	}

	public synchronized int pending() {
		return mempool.size();
	}
}
