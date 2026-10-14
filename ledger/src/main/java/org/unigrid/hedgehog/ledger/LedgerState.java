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
	private static final byte[] SUPPLY = { 'S' };
	private static final byte[] ACCOUNT = { 'A' };
	private static final byte[] REFERENCE = { 'M' };
	private static final byte[] VALIDATOR = { 'V' };
	private static final byte[] PREVIOUS_VALIDATOR = { 'Q' };
	private static final byte[] VOTE = { 'T' };
	private static final byte[] UNCLAIMED = { 'U' };
	private static final byte[] POOL = { 'P' };
	private static final byte[] REDIRECT = { 'R' };

	private final LedgerGenesis genesis;
	private final LedgerAuthority authority;
	private final Snapshot snapshot;
	private final byte[] chainId;
	private final VerifiedSignatures verified;
	private Map<Address, Long> accounts = new HashMap<>();

	/* A legacy address mapped to the account that claimed it */
	private Map<Address, Address> redirects = new HashMap<>();
	private Map<Address, Long> nonces = new HashMap<>();
	private Set<Reference> minted = new HashSet<>();
	private Set<VoteCast> votes = new TreeSet<>();
	private ValidatorSet validators;
	private ValidatorSet previousValidators;
	private long height;
	private long supply;
	private long unclaimed;
	private long pool;
	private byte[] tipHash;
	private long tipTime;
	private Certificate tipCertificate = Certificate.EMPTY;
	private long authorityTime;

	/* Set only on the scratch copy a certified block is checked against. More than 2/3 of the validators signed
	   that block after asking their authorities, so this node's own authority, which may not know the mint yet
	   or any longer, is not asked again and cannot make it disagree with the others. */
	private boolean vouched;

	public LedgerState(LedgerGenesis genesis, LedgerAuthority authority) {
		this(genesis, authority, Snapshot.EMPTY);
	}

	public LedgerState(LedgerGenesis genesis, LedgerAuthority authority, Snapshot snapshot) {
		this.genesis = genesis;
		this.authority = authority;
		this.snapshot = snapshot;
		this.chainId = genesis.chainId();
		this.verified = new VerifiedSignatures();
		this.validators = genesis.getValidators();
		this.previousValidators = genesis.getValidators();
		this.supply = genesis.getSnapshotTotal();
		this.unclaimed = genesis.getSnapshotTotal();
		this.tipHash = chainId;
		this.tipTime = genesis.getTime();
		this.authorityTime = genesis.getTime();
	}

	private LedgerState(LedgerState other) {
		genesis = other.genesis;
		authority = other.authority;
		snapshot = other.snapshot;
		chainId = other.chainId;
		verified = other.verified;
		accounts = new HashMap<>(other.accounts);
		redirects = new HashMap<>(other.redirects);
		nonces = new HashMap<>(other.nonces);
		minted = new HashSet<>(other.minted);
		votes = new TreeSet<>(other.votes);
		validators = other.validators;
		previousValidators = other.previousValidators;
		height = other.height;
		supply = other.supply;
		unclaimed = other.unclaimed;
		pool = other.pool;
		tipHash = other.tipHash;
		tipTime = other.tipTime;
		tipCertificate = other.tipCertificate;
		authorityTime = other.authorityTime;
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

	/* Everything that can be spent: the snapshot of the earlier chain the genesis commits to, and every mint since */
	public long supply() {
		return supply;
	}

	/* What of the snapshot total no claim has taken yet */
	public long unclaimed() {
		return unclaimed;
	}

	/* The time the authority is asked about: the time of a block when a block is checked, the time of the node
	   for what it takes in or proposes. Whoever owns this state sets it before asking. */
	void askAuthorityAt(long time) {
		authorityTime = time;
	}

	/* The mints that are due at this time and not yet made, in the order the authority lists them */
	public List<Mint> dueMints(long time) {
		return authority.due(time).stream().filter(mint -> !minted.contains(mint.reference())).toList();
	}

	/* How many transfers an account has made: the nonce its next transfer must carry */
	public long nonceOf(AccountKey account) {
		return nonces.getOrDefault(account, 0L);
	}

	/* Fees waiting to be paid out to the validators */
	public long pool() {
		return pool;
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

	public long balanceOf(Address key) {
		return accounts.getOrDefault(key, 0L);
	}

	public boolean hasMinted(Reference reference) {
		return minted.contains(reference);
	}

	public Optional<String> rejectionOf(Transaction transaction) {
		return switch (transaction) {
			case Mint mint -> rejectionOf(mint);
			case Vote vote -> rejectionOf(vote);
			case Claim claim -> rejectionOf(claim);
			case Transfer transfer -> rejectionOf(transfer);
		};
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
		redirects = next.redirects;
		nonces = next.nonces;
		minted = next.minted;
		votes = next.votes;
		validators = next.validators;
		previousValidators = next.previousValidators;
		height = next.height;
		supply = next.supply;
		unclaimed = next.unclaimed;
		pool = next.pool;
		tipHash = next.tipHash;
		tipTime = next.tipTime;
		tipCertificate = next.tipCertificate;
	}

	/* The candidates that can go in one block, in order: each is kept if it is still valid after the ones kept
	   before it. The state itself is not touched. */
	public List<Transaction> applicable(List<Transaction> candidates) {
		final LedgerState scratch = copy();
		final List<Transaction> kept = new ArrayList<>();

		scratch.payFees(tipCertificate);

		for (final Transaction candidate : candidates) {
			if (scratch.rejectionOf(candidate).isEmpty()) {
				scratch.apply(candidate);
				kept.add(candidate);
			}
		}

		return kept;
	}

	/* What the root would be if these transactions made the block at blockHeight, for a proposer to commit to. A
	   proposer carries the tip certificate as last commit, so that is what is paid. */
	public byte[] rootAfter(long blockHeight, List<Transaction> transactions) {
		final LedgerState next = copy();

		next.payFees(tipCertificate);

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
		leaves.add(Bytes.concat(SUPPLY, Bytes.longBytes(supply)));
		leaves.add(Bytes.concat(UNCLAIMED, Bytes.longBytes(unclaimed)));
		leaves.add(Bytes.concat(POOL, Bytes.longBytes(pool)));
		accounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> leaves
			.add(Bytes.concat(ACCOUNT, entry.getKey().encode(), Bytes.longBytes(entry.getValue()),
				Bytes.longBytes(nonces.getOrDefault(entry.getKey(), 0L)))));
		redirects.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> leaves
			.add(Bytes.concat(REDIRECT, entry.getKey().encode(), entry.getValue().encode())));
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

		/* No balance can pass the supply, so the supply is the one sum to guard */
		if (supply > Long.MAX_VALUE - mint.amount()) {
			return Optional.of("Supply would overflow");
		}

		return vouched || authority.authorizes(mint, authorityTime) ? Optional.empty()
			: Optional.of("Mint is not authorized");
	}

	private Optional<String> rejectionOf(Transfer transfer) {
		final long next = nonceOf(transfer.sender());

		return transfer.nonce() == next ? rejectionOfQueued(transfer)
			: Optional.of("Transfer nonce is " + transfer.nonce() + " but the next is " + next);
	}

	/* A transfer that waits for the ones before it is as good as can be known now: everything but the exact
	   turn of its nonce is checked, which is what the mempool needs to queue it */
	public Optional<String> rejectionOfQueued(Transfer transfer) {
		return usedRejection(transfer).or(() -> coverRejection(transfer)).or(() -> signatureRejection(transfer))
			.or(() -> authorityRejection(transfer));
	}

	private Optional<String> usedRejection(Transfer transfer) {
		return transfer.nonce() < nonceOf(transfer.sender()) ? Optional.of("Transfer nonce is already used")
			: Optional.empty();
	}

	private Optional<String> coverRejection(Transfer transfer) {
		if (transfer.fee() > Long.MAX_VALUE - transfer.amount()) {
			return Optional.of("Transfer would overflow");
		}

		return balanceOf(transfer.sender()) < transfer.amount() + transfer.fee()
			? Optional.of("Balance does not cover the transfer") : Optional.empty();
	}

	private Optional<String> signatureRejection(Transfer transfer) {
		return verified.check(transfer, () -> transfer.hasValidSignature(chainId))
			? Optional.empty() : Optional.of("Transfer signature is not valid");
	}

	private Optional<String> authorityRejection(Transfer transfer) {
		return vouched || authority.authorizes(transfer) ? Optional.empty()
			: Optional.of("Fee is below what this node demands");
	}

	private Optional<String> rejectionOf(Claim claim) {
		return amountRejection(claim.address()).or(() -> signatureRejection(claim))
			.or(() -> authorityRejection(claim));
	}

	/* What the snapshot holds is checked against what is left of its total, so a snapshot that does not add up to
	   the genesis total cannot be drained past it. Once it is known not to be negative, what is owed is at most the
	   unclaimed part of the supply and the ledger balance of the address at most what was minted to it, so the two
	   together cannot pass the supply. */
	private Optional<String> amountRejection(LegacyAddress address) {
		final long owed = snapshot.balanceOf(address);

		if (redirects.containsKey(address)) {
			return Optional.of("Legacy address is already claimed");
		}

		if (owed < 0) {
			return Optional.of("Snapshot balance is negative");
		}

		if (owed > unclaimed) {
			return Optional.of("Claim is above what the snapshot has left");
		}

		return owed + balanceOf(address) == 0 ? Optional.of("Nothing to claim") : Optional.empty();
	}

	private Optional<String> signatureRejection(Claim claim) {
		return verified.check(claim, () -> claim.hasValidSignature(chainId))
			? Optional.empty() : Optional.of("Claim signature is not valid");
	}

	private Optional<String> authorityRejection(Claim claim) {
		return vouched || authority.authorizes(claim) ? Optional.empty() : Optional.of("Claim is not authorized");
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

	/* Whether a block is right apart from the signatures of its certificate, which is what a validator checks
	   before it signs: its place in the chain, the view of its certificate, its last commit (a quorum of the
	   set that signed the tip, with valid precommits for the tip), its transactions and the root they lead to */
	public Optional<String> proposalRejection(Block block) {
		final LedgerState next = copy();

		next.askAuthorityAt(block.getTime());

		final Optional<String> rejection = headerRejection(block).or(() -> viewRejection(block))
			.or(() -> lastCommitRejection(block)).or(() -> lastCommitSignaturesRejection(block))
			.or(() -> next.applyTransactions(block));

		if (rejection.isPresent()) {
			return rejection;
		}

		next.finishBlock(block.getHeight());
		return next.rootRejection(block);
	}

	private Optional<String> applyTransactions(Block block) {
		payFees(block.getLastCommit());

		for (final Transaction transaction : block.getTransactions()) {
			final Optional<String> rejection = rejectionOf(transaction);

			if (rejection.isPresent()) {
				return rejection;
			}

			apply(transaction);
		}

		return Optional.empty();
	}

	/* The fees of the last block go to the validators that signed it, in equal parts, and what does not divide
	   goes one unit each to the first signers in key order. The signers are the ones the block's own last
	   commit names, which has been proven to be the commit of the tip. */
	private void payFees(Certificate lastCommit) {
		if (pool == 0) {
			return;
		}

		final List<Endorsement> signers = lastCommit.endorsements();
		final long share = pool / signers.size();
		final long extra = pool % signers.size();

		for (int i = 0; i < signers.size(); i++) {
			accounts.merge(signers.get(i).signer(), share + (i < extra ? 1 : 0), Math::addExact);
		}

		pool = 0;
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
	   when its proposer has gone. It cannot be committed in an earlier one. */
	private Optional<String> certificateRejection(Block block) {
		return viewRejection(block).or(() -> shapeRejection("Certificate", block.getCertificate(), validators));
	}

	private static Optional<String> viewRejection(Block block) {
		return block.getCertificate().view() >= block.getView() ? Optional.empty()
			: Optional.of("Certificate is from an earlier view than the block");
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

	/* A block with nothing in it exists to pay out fees that wait, so a quiet chain never holds them back */
	private Optional<String> contentRejection(Block block) {
		final int count = block.getTransactions().size();

		if (count > Block.MAX_TRANSACTIONS || (count == 0 && pool == 0)) {
			return Optional.of("A block holds " + (pool > 0 ? 0 : 1) + " to " + Block.MAX_TRANSACTIONS
				+ " transactions");
		}

		if (!Arrays.equals(block.getTransactionRoot(), Block.transactionRootOf(block.getTransactions()))) {
			return Optional.of("Transaction root does not match");
		}

		return Optional.empty();
	}

	private void apply(Transaction transaction) {
		switch (transaction) {
			case Mint mint -> apply(mint);
			case Vote vote -> votes.add(new VoteCast(vote.candidate(), vote.action(), vote.voter()));
			case Claim claim -> apply(claim);
			case Transfer transfer -> apply(transfer);
		}
	}

	/* A claimed legacy address no longer holds anything: what is minted to it goes to whoever claimed it */
	private void apply(Mint mint) {
		accounts.merge(redirects.getOrDefault(mint.recipient(), mint.recipient()), mint.amount(), Math::addExact);
		supply += mint.amount();
		minted.add(mint.reference());
	}

	/* The snapshot balance is already in the supply, so a claim moves it and grows nothing */
	private void apply(Claim claim) {
		final LegacyAddress address = claim.address();
		final long owed = snapshot.balanceOf(address);

		accounts.merge(claim.recipient(), Math.addExact(owed, accounts.getOrDefault(address, 0L)), Math::addExact);
		accounts.remove(address);
		redirects.put(address, claim.recipient());
		minted.add(Reference.ofClaim(genesis.getSnapshotHash(), address));
		unclaimed -= owed;
	}

	/* The fee leaves the sender for the pool, so nothing is created or lost; a legacy address that was claimed
	   is paid to whoever claimed it, as a mint is */
	private void apply(Transfer transfer) {
		accounts.put(transfer.sender(), balanceAfter(transfer));
		accounts.merge(redirects.getOrDefault(transfer.recipient(), transfer.recipient()), transfer.amount(),
			Math::addExact);
		nonces.put(transfer.sender(), Math.addExact(transfer.nonce(), 1L));
		pool = Math.addExact(pool, transfer.fee());
	}

	/* A transfer is checked before it is applied; this makes an unchecked one fail instead of overdraw */
	private long balanceAfter(Transfer transfer) {
		final long left = balanceOf(transfer.sender()) - Math.addExact(transfer.amount(), transfer.fee());

		if (left < 0) {
			throw new IllegalStateException("A transfer would overdraw " + transfer.sender());
		}

		return left;
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
