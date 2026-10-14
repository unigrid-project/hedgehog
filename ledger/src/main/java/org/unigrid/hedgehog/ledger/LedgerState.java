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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class LedgerState {
	/* Votes stay in the state, and in its root, until the round ends, so each validator gets a fixed number */
	public static final int MAX_VOTES_PER_VALIDATOR = 64;

	private static final byte[] HEIGHT = { 'H' };
	private static final byte[] ACCOUNT = { 'A' };
	private static final byte[] REFERENCE = { 'M' };
	private static final byte[] VALIDATOR = { 'V' };
	private static final byte[] PREVIOUS_VALIDATOR = { 'Q' };
	private static final byte[] VOTE = { 'T' };

	private final LedgerGenesis genesis;
	private final MintAuthority authority;
	private final byte[] chainId;
	private Map<AccountKey, Long> accounts = new HashMap<>();
	private Set<Reference> minted = new HashSet<>();
	private Set<VoteCast> votes = new TreeSet<>();
	private ValidatorSet validators;
	private ValidatorSet previousValidators;
	private long height;
	private byte[] tipHash;
	private long tipTime;
	private Certificate tipCertificate = Certificate.EMPTY;

	/* Set only on the scratch copy a certified block is checked against. More than 2/3 of the validators signed
	   that block after asking their authorities, so this node's own authority, which may not know the mint yet
	   or any longer, is not asked again and cannot make it disagree with the others. */
	private boolean vouched;

	public LedgerState(LedgerGenesis genesis, MintAuthority authority) {
		this.genesis = genesis;
		this.authority = authority;
		this.chainId = genesis.chainId();
		this.validators = genesis.getValidators();
		this.previousValidators = genesis.getValidators();
		this.tipHash = chainId;
		this.tipTime = genesis.getTime();
	}

	private LedgerState(LedgerState other) {
		genesis = other.genesis;
		authority = other.authority;
		chainId = other.chainId;
		accounts = new HashMap<>(other.accounts);
		minted = new HashSet<>(other.minted);
		votes = new TreeSet<>(other.votes);
		validators = other.validators;
		previousValidators = other.previousValidators;
		height = other.height;
		tipHash = other.tipHash;
		tipTime = other.tipTime;
		tipCertificate = other.tipCertificate;
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

	public Certificate tipCertificate() {
		return tipCertificate;
	}

	/* The round the next block belongs to; a round is roundLength blocks, counted from 0 */
	public long round() {
		return height / genesis.getRoundLength();
	}

	public ValidatorSet validators() {
		return validators;
	}

	/* The validators in force when the tip block was checked, who are the ones that signed it */
	public ValidatorSet previousValidators() {
		return previousValidators;
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

		if (transaction instanceof Vote vote) {
			return rejectionOf(vote);
		}

		return Optional.of("Unsupported transaction kind");
	}

	public int votesFor(AccountKey candidate, Vote.Action action) {
		return (int) votes.stream().filter(cast -> cast.candidate().equals(candidate) && cast.action() == action)
			.count();
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
		votes = next.votes;
		validators = next.validators;
		previousValidators = next.previousValidators;
		height = next.height;
		tipHash = next.tipHash;
		tipTime = next.tipTime;
		tipCertificate = next.tipCertificate;
	}

	/* The candidates that can go in one block, in order: each is kept if it is still valid after the ones kept
	   before it. The state itself is not touched. */
	public List<Transaction> applicable(List<Transaction> candidates) {
		final LedgerState scratch = copy();
		final List<Transaction> kept = new ArrayList<>();

		for (final Transaction candidate : candidates) {
			if (scratch.rejectionOf(candidate).isEmpty()) {
				scratch.apply(candidate);
				kept.add(candidate);
			}
		}

		return kept;
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

		next.finishBlock(blockHeight);
		return next.stateRoot();
	}

	/* The last block of a round closes it: the changes more than 2/3 of the set voted for take effect for the
	   next round, and the votes start over */
	private void finishBlock(long blockHeight) {
		height = blockHeight;
		previousValidators = validators;

		if (blockHeight % genesis.getRoundLength() == 0) {
			endRound();
		}
	}

	private void endRound() {
		final Map<Choice, Long> tally = votes.stream().collect(Collectors.groupingBy(
			cast -> new Choice(cast.candidate(), cast.action()), LinkedHashMap::new, Collectors.counting()));
		final List<AccountKey> removed = new ArrayList<>();
		final List<AccountKey> added = new ArrayList<>();

		tally.forEach((choice, count) -> {
			if (count * 3 > validators.size() * 2L) {
				(choice.action() == Vote.Action.ADD ? added : removed).add(choice.candidate());
			}
		});

		final List<AccountKey> keys = validators.keys().stream().filter(key -> !removed.contains(key))
			.collect(Collectors.toCollection(ArrayList::new));

		/* Several additions may pass in one round and not all fit: the first in key order get the places */
		added.stream().limit(ValidatorSet.MAX_SIZE - keys.size()).forEach(keys::add);
		validators = new ValidatorSet(keys);
		votes.clear();
	}

	private record Choice(AccountKey candidate, Vote.Action action) {
	}

	public byte[] stateRoot() {
		final List<byte[]> leaves = new ArrayList<>();

		leaves.add(Bytes.concat(HEIGHT, Bytes.longBytes(height)));
		accounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> leaves
			.add(Bytes.concat(ACCOUNT, entry.getKey().bytes(), Bytes.longBytes(entry.getValue()))));
		minted.stream().sorted().forEach(reference -> leaves.add(Bytes.concat(REFERENCE, reference.bytes())));
		validators.keys().forEach(key -> leaves.add(Bytes.concat(VALIDATOR, key.bytes())));
		previousValidators.keys().forEach(key -> leaves.add(Bytes.concat(PREVIOUS_VALIDATOR, key.bytes())));
		votes.forEach(cast -> leaves.add(Bytes.concat(VOTE, cast.candidate().bytes(),
			new byte[] { cast.action().wire() }, cast.voter().bytes())));
		return MerkleRoot.of(leaves);
	}

	private Optional<String> rejectionOf(Vote vote) {
		return voterRejection(vote).or(() -> candidateRejection(vote)).or(() -> signatureRejection(vote));
	}

	private Optional<String> voterRejection(Vote vote) {
		if (!validators.contains(vote.voter())) {
			return Optional.of("Voter is not a validator");
		}

		if (vote.round() != round()) {
			return Optional.of("Vote is for another round");
		}

		return countRejection(vote);
	}

	private Optional<String> countRejection(Vote vote) {
		if (votes.stream().anyMatch(cast -> cast.candidate().equals(vote.candidate())
			&& cast.voter().equals(vote.voter()))) {
			return Optional.of("Voter has already voted on this candidate");
		}

		return votes.stream().filter(cast -> cast.voter().equals(vote.voter())).count() >= MAX_VOTES_PER_VALIDATOR
			? Optional.of("Voter has cast too many votes this round") : Optional.empty();
	}

	/* A key is added only when it is not in the set and removed only when it is, and a foundation validator
	   from the genesis is never removed: they stay on as the backup */
	private Optional<String> candidateRejection(Vote vote) {
		final boolean member = validators.contains(vote.candidate());

		if (vote.action() == Vote.Action.ADD) {
			if (member) {
				return Optional.of("Candidate is already a validator");
			}

			return validators.size() >= ValidatorSet.MAX_SIZE ? Optional.of("The validator set is full")
				: Optional.empty();
		}

		if (!member) {
			return Optional.of("Candidate is not a validator");
		}

		return genesis.getValidators().contains(vote.candidate())
			? Optional.of("Genesis validators cannot be removed") : Optional.empty();
	}

	private Optional<String> signatureRejection(Vote vote) {
		return vote.hasValidSignature(chainId) ? Optional.empty() : Optional.of("Vote signature is not valid");
	}

	private Optional<String> rejectionOf(Mint mint) {
		if (minted.contains(mint.reference())) {
			return Optional.of("Reference already used");
		}

		if (balanceOf(mint.recipient()) > Long.MAX_VALUE - mint.amount()) {
			return Optional.of("Balance would overflow");
		}

		return vouched || authority.authorizes(mint) ? Optional.empty() : Optional.of("Mint is not authorized");
	}

	/* Applies the block to next, which the caller owns, so the state it was called on is never touched. The
	   cheap checks come first and the signature checks after them. */
	private Optional<String> rejectionOf(Block block, LedgerState next) {
		next.vouched = true;

		final Optional<String> rejection = headerRejection(block).or(() -> certificateRejection(block))
			.or(() -> lastCommitRejection(block)).or(() -> signaturesRejection(block))
			.or(() -> lastCommitSignaturesRejection(block)).or(() -> next.applyTransactions(block));

		if (rejection.isPresent()) {
			return rejection;
		}

		next.finishBlock(block.getHeight());

		final Optional<String> sealed = next.rootRejection(block);

		if (sealed.isEmpty()) {
			next.tipHash = block.hash();
			next.tipTime = block.getTime();
			next.tipCertificate = block.getCertificate();
		}

		return sealed;
	}

	/* Whether a block is right apart from its certificate, which is what a validator checks before it signs:
	   its place in the chain, its transactions and the root they lead to */
	public Optional<String> proposalRejection(Block block) {
		final LedgerState next = copy();
		final Optional<String> rejection = headerRejection(block).or(() -> lastCommitRejection(block))
			.or(() -> lastCommitSignaturesRejection(block)).or(() -> next.applyTransactions(block));

		if (rejection.isPresent()) {
			return rejection;
		}

		next.finishBlock(block.getHeight());
		return next.rootRejection(block);
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

	/* On a state that has the block applied: that its root is the block's */
	private Optional<String> rootRejection(Block block) {
		return Arrays.equals(block.getStateRoot(), stateRoot()) ? Optional.empty()
			: Optional.of("State root does not match");
	}

	/* Each signature in the certificate must be a precommit for this very block, in the view the certificate
	   names. This comes last because it costs the most. */
	private Optional<String> signaturesRejection(Block block) {
		return block.getEndorsements().stream().allMatch(endorsement -> block.isSigned(endorsement, chainId))
			? Optional.empty() : Optional.of("Certificate holds a signature that is not valid for this block");
	}

	/* The proposer need not be among the signers: a block proposed in one view can be committed in a later one,
	   when its proposer has gone. */
	private Optional<String> certificateRejection(Block block) {
		return shapeRejection("Certificate", block.getCertificate(), validators);
	}

	/* The block before this one was committed by the validators of its time, which are not always today's: the
	   block that ended a round may have changed the set. The first block has nothing to carry. */
	private Optional<String> lastCommitRejection(Block block) {
		if (height == 0) {
			return block.getLastCommit().equals(Certificate.EMPTY) ? Optional.empty()
				: Optional.of("The first block has no last commit");
		}

		return shapeRejection("Last commit", block.getLastCommit(), previousValidators);
	}

	private Optional<String> lastCommitSignaturesRejection(Block block) {
		final Certificate commit = block.getLastCommit();

		return height == 0 || commit.endorsements().stream().allMatch(
			endorsement -> Precommit.isSigned(endorsement, chainId, height, commit.view(), tipHash))
			? Optional.empty()
			: Optional.of("Last commit holds a signature that is not valid for the previous block");
	}

	/* Who signed, before any signature is checked: more than 2/3 of the set, each once and in order */
	private static Optional<String> shapeRejection(String name, Certificate certificate, ValidatorSet signers) {
		final List<Endorsement> endorsements = certificate.endorsements();

		if (endorsements.size() < signers.quorum()) {
			return Optional.of(name + " has no quorum");
		}

		if (!endorsements.stream().allMatch(endorsement -> signers.contains(endorsement.signer()))) {
			return Optional.of(name + " holds a signer that is not a validator");
		}

		return IntStream.range(1, endorsements.size()).allMatch(i -> endorsements.get(i - 1).signer()
			.compareTo(endorsements.get(i).signer()) < 0) ? Optional.empty()
			: Optional.of(name + " is not sorted with each signer once");
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

		if (!block.getProposer().equals(validators.proposerAt(block.getHeight(), block.getView()))) {
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
		if (transaction instanceof Mint mint) {
			accounts.merge(mint.recipient(), mint.amount(), Long::sum);
			minted.add(mint.reference());
		} else if (transaction instanceof Vote vote) {
			votes.add(new VoteCast(vote.candidate(), vote.action(), vote.voter()));
		}
	}

	private record VoteCast(AccountKey candidate, Vote.Action action, AccountKey voter)
		implements Comparable<VoteCast> {
		private static final Comparator<VoteCast> ORDER = Comparator.comparing(VoteCast::candidate)
			.thenComparing(VoteCast::action).thenComparing(VoteCast::voter);

		@Override
		public int compareTo(VoteCast other) {
			return ORDER.compare(this, other);
		}
	}
}
