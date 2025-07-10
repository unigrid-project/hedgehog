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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

public final class LedgerState {
	private static final byte[] HEIGHT = { 'H' };
	private static final byte[] ACCOUNT = { 'A' };
	private static final byte[] REFERENCE = { 'M' };
	private static final byte[] VALIDATOR = { 'V' };

	private final LedgerGenesis genesis;
	private final MintAuthority authority;
	private final byte[] chainId;
	private Map<AccountKey, Long> accounts = new HashMap<>();
	private Set<Reference> minted = new HashSet<>();
	private ValidatorSet validators;
	private long height;
	private byte[] tipHash;
	private long tipTime;

	public LedgerState(LedgerGenesis genesis, MintAuthority authority) {
		this.genesis = genesis;
		this.authority = authority;
		this.chainId = genesis.chainId();
		this.validators = genesis.getValidators();
		this.tipHash = chainId;
		this.tipTime = genesis.getTime();
	}

	private LedgerState(LedgerState other) {
		genesis = other.genesis;
		authority = other.authority;
		chainId = other.chainId;
		accounts = new HashMap<>(other.accounts);
		minted = new HashSet<>(other.minted);
		validators = other.validators;
		height = other.height;
		tipHash = other.tipHash;
		tipTime = other.tipTime;
	}

	public LedgerState copy() {
		return new LedgerState(this);
	}

	public byte[] chainId() {
		return chainId.clone();
	}

	public long height() {
		return height;
	}

	public byte[] tipHash() {
		return tipHash.clone();
	}

	public long tipTime() {
		return tipTime;
	}

	/* The round the next block belongs to; a round is roundLength blocks, counted from 0 */
	public long round() {
		return height / genesis.getRoundLength();
	}

	public ValidatorSet validators() {
		return validators;
	}

	public long balanceOf(AccountKey key) {
		return accounts.getOrDefault(key, 0L);
	}

	public boolean hasMinted(Reference reference) {
		return minted.contains(reference);
	}

	public Optional<String> rejectionOf(Transaction transaction) {
		if (transaction instanceof Mint mint) {
			return rejectionOf(mint);
		}

		return Optional.of("Unsupported transaction kind");
	}

	public Optional<String> rejectionOf(Block block) {
		return rejectionOf(block, copy());
	}

	public void apply(Block block) {
		final LedgerState next = copy();
		final Optional<String> rejection = rejectionOf(block, next);

		if (rejection.isPresent()) {
			throw new InvalidBlockException(rejection.get());
		}

		accounts = next.accounts;
		minted = next.minted;
		validators = next.validators;
		height = next.height;
		tipHash = next.tipHash;
		tipTime = next.tipTime;
	}

	/* What the root would be if these transactions made the block at blockHeight, for a proposer to commit to */
	public byte[] rootAfter(long blockHeight, List<Transaction> transactions) {
		final LedgerState next = copy();

		for (final Transaction transaction : transactions) {
			final Optional<String> rejection = next.rejectionOf(transaction);

			if (rejection.isPresent()) {
				throw new IllegalArgumentException(rejection.get());
			}

			next.apply(transaction);
		}

		next.height = blockHeight;
		return next.stateRoot();
	}

	public byte[] stateRoot() {
		final List<byte[]> leaves = new ArrayList<>();

		leaves.add(Bytes.concat(HEIGHT, Bytes.longBytes(height)));
		accounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> leaves
			.add(Bytes.concat(ACCOUNT, entry.getKey().bytes(), Bytes.longBytes(entry.getValue()))));
		minted.stream().sorted().forEach(reference -> leaves.add(Bytes.concat(REFERENCE, reference.bytes())));
		validators.keys().forEach(key -> leaves.add(Bytes.concat(VALIDATOR, key.bytes())));
		return MerkleRoot.of(leaves);
	}

	private Optional<String> rejectionOf(Mint mint) {
		if (minted.contains(mint.reference())) {
			return Optional.of("Reference already used");
		}

		if (balanceOf(mint.recipient()) > Long.MAX_VALUE - mint.amount()) {
			return Optional.of("Balance would overflow");
		}

		return authority.authorizes(mint) ? Optional.empty() : Optional.of("Mint is not authorized");
	}

	/* Applies the block to next, which the caller owns, so the state it was called on is never touched. The
	   cheap checks come first and the signature checks last. */
	private Optional<String> rejectionOf(Block block, LedgerState next) {
		final Optional<String> rejection = headerRejection(block).or(() -> certificateRejection(block))
			.or(() -> next.applyTransactions(block));

		if (rejection.isPresent()) {
			return rejection;
		}

		next.height = block.getHeight();
		return next.sealRejection(block);
	}

	private Optional<String> applyTransactions(Block block) {
		for (final Transaction transaction : block.getTransactions()) {
			final Optional<String> rejection = rejectionOf(transaction);

			if (rejection.isPresent()) {
				return rejection;
			}

			apply(transaction);
		}

		return Optional.empty();
	}

	/* The last checks, on a state that has the block applied: that its root is the block's and that each
	   signature in the certificate is over this very block. They come last because they cost the most. */
	private Optional<String> sealRejection(Block block) {
		if (!Arrays.equals(block.getStateRoot(), stateRoot())) {
			return Optional.of("State root does not match");
		}

		if (!block.getEndorsements().stream().allMatch(block::isSigned)) {
			return Optional.of("Certificate holds a signature that is not valid for this block");
		}

		tipHash = block.hash();
		tipTime = block.getTime();
		return Optional.empty();
	}

	/* Who signed, before any signature is checked: more than 2/3 of the set, each once and in order, the
	   proposer among them. */
	private Optional<String> certificateRejection(Block block) {
		final List<Endorsement> endorsements = block.getEndorsements();

		if (endorsements.size() < validators.quorum()) {
			return Optional.of("Certificate has no quorum");
		}

		if (!endorsements.stream().allMatch(endorsement -> validators.contains(endorsement.signer()))) {
			return Optional.of("Certificate holds a signer that is not a validator");
		}

		if (!IntStream.range(1, endorsements.size()).allMatch(i -> endorsements.get(i - 1).signer()
			.compareTo(endorsements.get(i).signer()) < 0)) {
			return Optional.of("Certificate is not sorted with each signer once");
		}

		return endorsements.stream().anyMatch(endorsement -> endorsement.signer().equals(block.getProposer()))
			? Optional.empty() : Optional.of("Certificate lacks the proposer");
	}

	private Optional<String> headerRejection(Block block) {
		final Optional<String> position = positionRejection(block);

		return position.isPresent() ? position : contentRejection(block);
	}

	private Optional<String> positionRejection(Block block) {
		if (block.getHeight() != height + 1) {
			return Optional.of("Expected height " + (height + 1) + " but found " + block.getHeight());
		}

		if (!Arrays.equals(block.getPreviousHash(), tipHash)) {
			return Optional.of("Previous hash is not the tip");
		}

		if (!block.getProposer().equals(validators.proposerAt(block.getHeight()))) {
			return Optional.of("Block is not from the proposer of this height");
		}

		return block.getTime() < tipTime ? Optional.of("Block is older than the tip") : Optional.empty();
	}

	private Optional<String> contentRejection(Block block) {
		if (block.getTransactions().isEmpty() || block.getTransactions().size() > Block.MAX_TRANSACTIONS) {
			return Optional.of("A block holds 1 to " + Block.MAX_TRANSACTIONS + " transactions");
		}

		if (!Arrays.equals(block.getTransactionRoot(), Block.transactionRootOf(block.getTransactions()))) {
			return Optional.of("Transaction root does not match");
		}

		return Optional.empty();
	}

	private void apply(Transaction transaction) {
		final Mint mint = (Mint) transaction;

		accounts.merge(mint.recipient(), mint.amount(), Long::sum);
		minted.add(mint.reference());
	}
}
