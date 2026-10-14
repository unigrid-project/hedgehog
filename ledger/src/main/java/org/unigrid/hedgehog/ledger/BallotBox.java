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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/* The ballots of one height as they come in: who said what in which view. The box checks the signature of every
   ballot it has not seen before, so a forged ballot can neither count towards a quorum nor become evidence. A
   validator that says two different things in one view is recorded once and only its first word counts, so a
   validator cannot be counted twice. It also remembers the highest view each validator was heard in, for the
   rule that skips ahead when more than 1/3 of the validators are already further on. Every map keeps its
   insertion order, so two runs that see the same ballots in the same order answer in the same order. */
final class BallotBox {
	private final long height;
	private final ValidatorSet validators;
	private final byte[] chainId;
	private final Map<Slot, Map<AccountKey, Ballot>> ballots = new LinkedHashMap<>();
	private final Map<AccountKey, List<Ballot>> ballotsByValidator = new LinkedHashMap<>();
	private final Map<AccountKey, Integer> highest = new LinkedHashMap<>();
	private final Map<EvidenceKey, Equivocation> evidence = new LinkedHashMap<>();

	BallotBox(long height, ValidatorSet validators, byte[] chainId) {
		this.height = height;
		this.validators = validators;
		this.chainId = chainId.clone();
	}

	enum Result {
		ADDED, DUPLICATE, EQUIVOCATION, REFUSED
	}

	record Equivocation(Ballot first, Ballot second) {
	}

	private record Slot(Ballot.Type type, int view) {
	}

	private record EvidenceKey(Slot slot, AccountKey validator) {
	}

	Result add(Ballot ballot) {
		if (isForeign(ballot)) {
			return Result.REFUSED;
		}

		final Ballot earlier = slot(ballot.type(), ballot.view()).get(ballot.validator());

		if (isKnown(earlier, ballot)) {
			return Result.DUPLICATE;
		}

		if (!ballot.hasValidSignature(chainId)) {
			return Result.REFUSED;
		}

		return earlier == null ? store(ballot) : recordEquivocation(earlier, ballot);
	}

	boolean has(Ballot ballot) {
		final Ballot earlier = slot(ballot.type(), ballot.view()).get(ballot.validator());

		return earlier != null && sameBlock(earlier, ballot);
	}

	void seen(AccountKey validator, int view) {
		if (view >= 0 && validators.contains(validator)) {
			highest.merge(validator, view, Math::max);
		}
	}

	int size() {
		return ballots.values().stream().mapToInt(Map::size).sum();
	}

	int count(Ballot.Type type, int view, byte[] hash) {
		return (int) slot(type, view).values().stream().filter(ballot -> Arrays.equals(ballot.blockHash(), hash))
			.count();
	}

	int countAny(Ballot.Type type, int view) {
		return slot(type, view).size();
	}

	boolean quorumFor(Ballot.Type type, int view, byte[] hash) {
		return count(type, view, hash) >= validators.quorum();
	}

	boolean quorumAny(Ballot.Type type, int view) {
		return countAny(type, view) >= validators.quorum();
	}

	List<Endorsement> endorsements(int view, byte[] hash) {
		return slot(Ballot.Type.PRECOMMIT, view).values().stream()
			.filter(ballot -> Arrays.equals(ballot.blockHash(), hash)).map(Ballot::endorsement)
			.sorted(Comparator.comparing(Endorsement::signer)).toList();
	}

	/* The view that more than 1/3 of the validators have reached, the highest such one: at least one of them is
	   honest, so the view is real */
	OptionalInt skipTo(int currentView) {
		final List<Integer> views = highest.values().stream().sorted(Comparator.reverseOrder()).toList();
		final int needed = validators.oneThird();

		return views.size() >= needed && views.get(needed - 1) > currentView
			? OptionalInt.of(views.get(needed - 1)) : OptionalInt.empty();
	}

	List<Ballot> ballotsBy(AccountKey validator) {
		return List.copyOf(ballotsByValidator.getOrDefault(validator, List.of()));
	}

	List<Equivocation> evidence() {
		return List.copyOf(evidence.values());
	}

	private boolean isForeign(Ballot ballot) {
		return ballot.height() != height || !validators.contains(ballot.validator());
	}

	/* A validator that already contradicted itself in this slot is old news, however many more things it says */
	private boolean isKnown(Ballot earlier, Ballot ballot) {
		return earlier != null && (sameBlock(earlier, ballot) || evidence.containsKey(evidenceKey(ballot)));
	}

	private Result store(Ballot ballot) {
		slotToFill(ballot).put(ballot.validator(), ballot);
		ballotsByValidator.computeIfAbsent(ballot.validator(), key -> new ArrayList<>()).add(ballot);
		seen(ballot.validator(), ballot.view());
		return Result.ADDED;
	}

	private Result recordEquivocation(Ballot earlier, Ballot ballot) {
		evidence.putIfAbsent(evidenceKey(ballot), new Equivocation(earlier, ballot));
		return Result.EQUIVOCATION;
	}

	private static EvidenceKey evidenceKey(Ballot ballot) {
		return new EvidenceKey(new Slot(ballot.type(), ballot.view()), ballot.validator());
	}

	private static boolean sameBlock(Ballot one, Ballot other) {
		return Arrays.equals(one.blockHash(), other.blockHash());
	}

	/* A question about a view nobody has voted in must not allocate a slot, or a far-ahead view would grow the box */
	private Map<AccountKey, Ballot> slot(Ballot.Type type, int view) {
		return ballots.getOrDefault(new Slot(type, view), Map.of());
	}

	private Map<AccountKey, Ballot> slotToFill(Ballot ballot) {
		return ballots.computeIfAbsent(new Slot(ballot.type(), ballot.view()), key -> new LinkedHashMap<>());
	}
}
