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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Two things wait here. The transactions that are to go into the next blocks, and the heartbeats that show
   the chain is alive: blocks only come when they are needed, so a quiet chain makes none for a long while,
   and the heartbeats are how it can still be told from a stalled one. */
public final class Mempool {
	public static final int DEFAULT_MAX_PENDING = 10_000;

	/* At most this many transfers wait for one sender: one for each nonce from its next one up, and the window
	   moves up only as transfers are committed and dropped */
	public static final int NONCE_WINDOW = 16;

	private final Map<Object, Transaction> pending = new LinkedHashMap<>();
	private final Map<AccountKey, Heartbeat> heartbeats = new HashMap<>();
	private final int maxPending;

	public Mempool() {
		this(DEFAULT_MAX_PENDING);
	}

	public Mempool(int maxPending) {
		this.maxPending = maxPending;
	}

	private record VoteSlot(AccountKey voter, AccountKey candidate) {
	}

	private record TransferSlot(AccountKey sender, long nonce) {
	}

	/* A slot is what a transaction is about: a mint by its reference, a vote by who votes on whom, a claim by the
	   legacy address, a transfer by its sender and nonce. A second one for the same slot has another id but could
	   never be applied after the first. */
	private static Object slotOf(Transaction transaction) {
		return switch (transaction) {
			case Mint mint -> mint.reference();
			case Vote vote -> new VoteSlot(vote.voter(), vote.candidate());
			case Claim claim -> claim.address();
			case Transfer transfer -> new TransferSlot(transfer.sender(), transfer.nonce());
		};
	}

	public synchronized boolean admit(Transaction transaction, LedgerState state) {
		return transaction instanceof Transfer transfer ? admit(transfer, state) : admitOther(transaction, state);
	}

	/* A transfer may wait for the ones before it. It is queued when its nonce lies in the window above the next
	   one, the state does not refuse it for any reason but its turn, and the balance covers it together with
	   everything else the sender has waiting. One for the same nonce replaces the first only by a higher fee. */
	private boolean admit(Transfer transfer, LedgerState state) {
		final Object slot = slotOf(transfer);
		final Transfer rival = pending.get(slot) instanceof Transfer found ? found : null;

		if (crowdedOut(rival, transfer) || !inWindow(transfer, state)
			|| state.rejectionOfQueued(transfer).isPresent() || !coveredByWaiting(transfer, rival, state)) {
			return false;
		}

		pending.put(slot, transfer);
		return true;
	}

	private boolean crowdedOut(Transfer rival, Transfer transfer) {
		return rival == null ? pending.size() >= maxPending : rival.fee() >= transfer.fee();
	}

	private static boolean inWindow(Transfer transfer, LedgerState state) {
		return transfer.nonce() - state.nonceOf(transfer.sender()) < NONCE_WINDOW;
	}

	/* What the sender has waiting, apart from the transfer this one replaces, may not pass what is left of the
	   balance after this one. The sum saturates instead of wrapping. */
	private boolean coveredByWaiting(Transfer transfer, Transfer replaced, LedgerState state) {
		final long left = state.balanceOf(transfer.sender()) - transfer.amount() - transfer.fee();
		final long others = pending.values().stream().filter(Transfer.class::isInstance).map(Transfer.class::cast)
			.filter(waiting -> waiting != replaced && waiting.sender().equals(transfer.sender()))
			.mapToLong(waiting -> waiting.amount() + waiting.fee())
			.reduce(0L, (sum, next) -> sum > Long.MAX_VALUE - next ? Long.MAX_VALUE : sum + next);

		return others <= left;
	}

	/* Refuses what is already waiting, what the state refuses, and anything that would pass the limits: the
	   mempool as a whole, and the votes waiting from each validator, as many as it may cast in a round.
	   Votes already cast are the state's to count, and a waiting vote it would refuse never makes a block. */
	private boolean admitOther(Transaction transaction, LedgerState state) {
		final Object slot = slotOf(transaction);

		if (pending.containsKey(slot) || pending.size() >= maxPending || exceedsVoteLimit(transaction)
			|| state.rejectionOf(transaction).isPresent()) {
			return false;
		}

		pending.put(slot, transaction);
		return true;
	}

	private boolean exceedsVoteLimit(Transaction transaction) {
		return transaction instanceof Vote vote && pending.values().stream()
			.filter(waiting -> waiting instanceof Vote other && other.voter().equals(vote.voter())).count()
			>= LedgerState.MAX_VOTES_PER_VALIDATOR;
	}

	/* Oldest first and left in place: they leave when a block that holds them is committed. The transfers of
	   one sender are handed out in nonce order, in the places its transfers hold, so a block can take them all. */
	public synchronized List<Transaction> take(int max) {
		final Map<AccountKey, ArrayDeque<Transfer>> byNonce = pending.values().stream()
			.filter(Transfer.class::isInstance).map(Transfer.class::cast)
			.sorted(Comparator.comparingLong(Transfer::nonce))
			.collect(Collectors.groupingBy(Transfer::sender, Collectors.toCollection(ArrayDeque::new)));
		final List<Transaction> ordered = new ArrayList<>();

		for (final Transaction waiting : pending.values()) {
			if (ordered.size() >= max) {
				break;
			}

			ordered.add(waiting instanceof Transfer transfer ? byNonce.get(transfer.sender()).poll() : waiting);
		}

		return ordered;
	}

	public synchronized int size() {
		return pending.size();
	}

	/* The tip moved, so what was pending is checked again against the new state, and every heartbeat, which
	   was for the old tip, is forgotten */
	public synchronized void removeCommitted(Block block, LedgerState state) {
		block.getTransactions().forEach(transaction -> pending.remove(slotOf(transaction)));
		pending.values().removeIf(transaction -> stale(transaction, state));
		heartbeats.clear();
	}

	/* A queued transfer stays while it is still good for a later turn; the others must be good now */
	private static boolean stale(Transaction transaction, LedgerState state) {
		return transaction instanceof Transfer transfer ? state.rejectionOfQueued(transfer).isPresent()
			: state.rejectionOf(transaction).isPresent();
	}

	/* Only the latest heartbeat of each validator is kept, and only one for the current tip from a validator
	   of the current set */
	public synchronized boolean admit(Heartbeat heartbeat, LedgerState state) {
		if (!isCurrent(heartbeat, state)) {
			return false;
		}

		final Heartbeat last = heartbeats.get(heartbeat.validator());

		if (last != null && last.time() >= heartbeat.time()) {
			return false;
		}

		if (!heartbeat.hasValidSignature(state.chainId())) {
			return false;
		}

		heartbeats.put(heartbeat.validator(), heartbeat);
		return true;
	}

	public synchronized List<Heartbeat> heartbeats() {
		return new ArrayList<>(heartbeats.values());
	}

	/* Alive when more than 2/3 of the validators have said so for the current tip within the window, on
	   either side of now, so a heartbeat dated far ahead cannot hold the answer up */
	public synchronized boolean isProgressing(LedgerState state, long now, long window) {
		if (now < 0) {
			throw new IllegalArgumentException("A time is 0 or more, found " + now);
		}

		return heartbeats.values().stream()
			.filter(heartbeat -> isCurrent(heartbeat, state) && Math.abs(now - heartbeat.time()) <= window)
			.count() >= state.validators().quorum();
	}

	private static boolean isCurrent(Heartbeat heartbeat, LedgerState state) {
		return state.validators().contains(heartbeat.validator()) && heartbeat.height() == state.height()
			&& Arrays.equals(heartbeat.tipHash(), state.tipHash());
	}
}
