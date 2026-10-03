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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/* Two things wait here. The transactions that are to go into the next blocks, and the heartbeats that show
   the chain is alive: blocks only come when they are needed, so a quiet chain makes none for a long while,
   and the heartbeats are how it can still be told from a stalled one. */
public final class Mempool {
	public static final int DEFAULT_MAX_PENDING = 10_000;

	private final Map<Object, Transaction> pending = new LinkedHashMap<>();
	private final Map<AccountKey, Heartbeat> heartbeats = new HashMap<>();
	private final int maxPending;

	public Mempool() {
		this(DEFAULT_MAX_PENDING);
	}

	public Mempool(int maxPending) {
		this.maxPending = maxPending;
	}

	/* A slot is what a transaction is about: a mint by its reference, a vote by who votes on whom. A second
	   one for the same slot has another id but could never be applied after the first. */
	private record VoteSlot(AccountKey voter, AccountKey candidate) {
	}

	private static Object slotOf(Transaction transaction) {
		if (transaction instanceof Mint mint) {
			return mint.reference();
		}

		final Vote vote = (Vote) transaction;

		return new VoteSlot(vote.voter(), vote.candidate());
	}

	/* Refuses what is already waiting, what the state refuses, and anything that would pass the limits: the
	   mempool as a whole, and each validator's waiting votes, which count as much as cast ones do */
	public synchronized boolean admit(Transaction transaction, LedgerState state) {
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

	/* Oldest first, and left in place: they leave when a block that holds them is committed */
	public synchronized List<Transaction> take(int max) {
		return new ArrayList<>(pending.values().stream().limit(max).toList());
	}

	public synchronized int size() {
		return pending.size();
	}

	/* The tip moved, so what was pending is checked again against the new state, and every heartbeat, which
	   was for the old tip, is forgotten */
	public synchronized void removeCommitted(Block block, LedgerState state) {
		block.getTransactions().forEach(transaction -> pending.remove(slotOf(transaction)));
		pending.values().removeIf(transaction -> state.rejectionOf(transaction).isPresent());
		heartbeats.clear();
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
		return heartbeats.values().stream()
			.filter(heartbeat -> isCurrent(heartbeat, state) && Math.abs(now - heartbeat.time()) <= window)
			.count() >= state.validators().quorum();
	}

	private static boolean isCurrent(Heartbeat heartbeat, LedgerState state) {
		return state.validators().contains(heartbeat.validator()) && heartbeat.height() == state.height()
			&& Arrays.equals(heartbeat.tipHash(), state.tipHash());
	}
}
