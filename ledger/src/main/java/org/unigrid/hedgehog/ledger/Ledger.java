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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/* The application of the consensus engine: it takes in transactions and heartbeats, says what a block of the next
   height would hold and whether one is right, and applies the ones the validators certified. What a validator has
   signed is the engine's to keep, so the ledger remembers no proposal. */
public final class Ledger implements ConsensusApp {
	private static final Logger LOGGER = LoggerFactory.getLogger(Ledger.class);
	private static final int MAX_REASON_LENGTH = 100;

	/* How far ahead of its own clock a validator lets the time of a block run: a proposer cannot make a mint due
	   more than this early by dating its block ahead. A validator whose clock runs late may therefore vote
	   for a mint up to this long before its time. */
	public static final long MAX_CLOCK_SKEW_MILLIS = 30_000;

	private final LedgerState state;
	private final BlockLog log;
	private final Mempool mempool = new Mempool();
	private final LongSupplier clock;

	public Ledger(LedgerState state, BlockLog log) {
		this(state, log, System::currentTimeMillis);
	}

	/* The log is replayed into the state, block by block and with every check, so a log that was tampered
	   with does not come up as a state */
	public Ledger(LedgerState state, BlockLog log, LongSupplier clock) {
		this.state = state;
		this.log = log;
		this.clock = clock;
		log.blocks().forEach(state::apply);
	}

	/* A check that throws is a check that could not be made, which is a refusal and not a failure of the ledger: the
	   authority belongs to the application and is asked about what peers send, so a peer must not be able to
	   take the node down with an item that makes it throw. A refusal of a block is a vote withheld, and nothing
	   is changed by a throw before the last step of an admission. */
	private static Optional<String> checked(Supplier<Optional<String>> check) {
		try {
			return check.get();
		} catch (RuntimeException e) {
			LOGGER.atWarn().setCause(e).log("A check could not be made");
			return Optional.of("Could not be checked: " + shortened(e.getMessage()));
		}
	}

	private static String shortened(String message) {
		return message == null || message.length() <= MAX_REASON_LENGTH ? String.valueOf(message)
			: message.substring(0, MAX_REASON_LENGTH);
	}

	/* Empty when the transaction went into the mempool, otherwise the reason it did not */
	public synchronized Optional<String> submit(Transaction transaction) {
		return checked(() -> admit(transaction));
	}

	private Optional<String> admit(Transaction transaction) {
		state.askAuthorityAt(clock.getAsLong());

		if (mempool.admit(transaction, state)) {
			return Optional.empty();
		}

		final Optional<String> reason = transaction instanceof Transfer transfer ? state.rejectionOfQueued(transfer)
			: state.rejectionOf(transaction);

		return Optional.of(reason.orElse(transaction instanceof Transfer
			? "Not queued: one waits for this nonce, or it is outside the window or not covered"
			: "Already waiting for a block"));
	}

	public synchronized Optional<String> submit(Heartbeat heartbeat) {
		return checked(() -> mempool.admit(heartbeat, state) ? Optional.empty()
			: Optional.of("Not a newer heartbeat for this tip from a validator"));
	}

	/* Empty when it is not the turn of this proposer in this view of the next height, or when nothing needs a block:
	   no transaction that can go in one, no mint that is due and no fee to pay out. The block has no endorsements,
	   the engine collects them. */
	@Override
	public synchronized Optional<Block> propose(long height, int view, AccountKey proposer, long time) {
		if (height != state.height() + 1 || !proposer.equals(state.validators().proposerAt(height, view))) {
			return Optional.empty();
		}

		final long blockTime = blockTimeFor(time);
		final List<Transaction> transactions = applicableAt(blockTime);

		if (transactions.isEmpty() && state.pool() == 0) {
			return Optional.empty();
		}

		return Optional.of(Block.builder().height(height).view(view).previousHash(state.tipHash())
			.time(blockTime).stateRoot(state.rootAfter(height, blockTime, transactions))
			.transactionRoot(Block.transactionRootOf(transactions)).transactions(transactions)
			.proposer(proposer).lastCommit(state.tipCertificate()).build());
	}

	/* The same test that propose makes, at the same time, so a quiet chain is one where no block could be made. The
	   engine asks it every second of a quiet chain, so when there is no fee to pay out, nothing waiting and no mint
	   due, the answer is given without the copy of the state that the check of a block makes. */
	@Override
	public synchronized boolean hasWork(long time) {
		final long blockTime = blockTimeFor(time);

		state.askAuthorityAt(blockTime);
		return !isPlainlyQuiet(blockTime) && (state.pool() > 0 || !applicableAt(blockTime).isEmpty());
	}

	private boolean isPlainlyQuiet(long blockTime) {
		return state.pool() == 0 && mempool.size() == 0 && state.dueMints(blockTime).isEmpty();
	}

	/* A block is never dated before the tip */
	private long blockTimeFor(long time) {
		return Math.max(time, state.tipTime());
	}

	/* The due mints come first, then what waits in the mempool. The block is cut after the checks, so a due mint
	   the authority holds back takes no place from the mempool, and what is cut off is the end of a valid
	   sequence, which stays valid. */
	private List<Transaction> applicableAt(long blockTime) {
		state.askAuthorityAt(blockTime);

		final List<Transaction> candidates = new ArrayList<>(state.dueMints(blockTime));

		candidates.addAll(mempool.proposable(Block.MAX_TRANSACTIONS, state));
		return state.applicable(blockTime, candidates).stream().limit(Block.MAX_TRANSACTIONS).toList();
	}

	/* What a validator checks before it votes for a block, a vote being a promise that the block is right: the
	   time of the block against the clock of this node, then the block against the next height. This node's own
	   policy counts. Nothing is changed. */
	@Override
	public synchronized Optional<String> validate(Block block) {
		if (block.getTime() > clock.getAsLong() + MAX_CLOCK_SKEW_MILLIS) {
			return Optional.of("Block time is more than " + MAX_CLOCK_SKEW_MILLIS
				+ " ms ahead of this node's clock");
		}

		return checked(() -> state.proposalRejection(block));
	}

	/* For certified blocks, which stand whatever the policy of this node says, so no policy is asked */
	@Override
	public synchronized Optional<String> validateCertified(Block block) {
		return checked(() -> state.rejectionOf(block));
	}

	/* The block is logged before the state changes, so a crash between the two replays to the same state and a
	   failed write leaves the state where it was; a block that is not valid is refused and leaves no trace. Only
	   the node commits, as a ledger that moved without the engine would leave the engine at a height that is decided:
	   a node that is behind is given the Decisions, in order. */
	synchronized void commit(Block block) throws IOException {
		final Optional<String> rejection = validateCertified(block);

		if (rejection.isPresent()) {
			throw new InvalidBlockException(rejection.get());
		}

		log.append(block);
		state.apply(block);
		state.askAuthorityAt(clock.getAsLong());
		mempool.removeCommitted(block, state);
	}

	/* Up to `max` committed blocks from the height `from`, in order, for a node that is behind, and never more than
	   a batch holds: the largest block there can be times that is within what a reader of a batch takes in. Read
	   from the log, so a request costs what it returns, not the length of the chain. This and `hashAt` only read
	   the log, which has its own lock and holds blocks that never change, so a request does not wait for the
	   ledger while it commits or checks a block. */
	public List<Block> blocksFrom(long from, int max) {
		if (from < 1 || max < 1) {
			throw new IllegalArgumentException("A request is from height 1 and for at least 1 block, found "
				+ from + " and " + max);
		}

		return log.range(from - 1, Math.min(max, BlockBatch.MAX_BLOCKS));
	}

	/* Empty at height 0, above the tip and below the first height */
	public Optional<byte[]> hashAt(long height) {
		return block(height).map(Block::hash);
	}

	/* Empty where there is no block: below the first height, or above the tip */
	public Optional<Block> block(long height) {
		return height < 1 ? Optional.empty() : blocksFrom(height, 1).stream().findFirst();
	}

	public synchronized Status status() {
		return new Status(state.chainId(), state.height(), state.tipHash());
	}

	public synchronized ValidatorSet validators() {
		return state.validators();
	}

	public synchronized long height() {
		return state.height();
	}

	public synchronized byte[] tipHash() {
		return state.tipHash();
	}

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

	public synchronized long balanceOf(Address key) {
		return state.balanceOf(key);
	}

	public synchronized long supply() {
		return state.supply();
	}

	/* What mints have made to this recipient so far, as the mints named it */
	public synchronized long mintedTo(Address recipient) {
		return state.mintedTo(recipient);
	}

	/* The nonce the next transfer of the account must carry */
	public synchronized long nonceOf(AccountKey account) {
		return state.nonceOf(account);
	}

	public synchronized Optional<AccountKey> claimedBy(LegacyAddress address) {
		return state.claimedBy(address);
	}

	public synchronized long claimable(LegacyAddress address) {
		return state.claimable(address);
	}

	public synchronized byte[] stateRoot() {
		return state.stateRoot();
	}

	public synchronized int pending() {
		return mempool.size();
	}

	/* What waits for a block, oldest first, in an order in which it can all be taken in again */
	synchronized List<Transaction> waiting() {
		return mempool.peek(Integer.MAX_VALUE);
	}

	/* Whether the transaction waits for a block here already */
	synchronized boolean holds(Transaction transaction) {
		return mempool.holds(transaction);
	}

	/* What of that a block could take now, oldest first, at most `max` of it */
	synchronized List<Transaction> waitingForABlock(int max) {
		return mempool.proposable(max, state);
	}

	/* The ledger that replaces this one takes in again what waited in the other, and what it now refuses is left out.
	   The pool is not on disk, so it is handed over, or the transactions that nodes took are lost with the node. */
	synchronized void takeWaitingFrom(Ledger replaced) {
		replaced.waiting().forEach(this::submit);
	}
}
