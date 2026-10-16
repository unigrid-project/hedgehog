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

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.LongFunction;
import java.util.function.Predicate;

/* An authority for tests with one answer per question; everything is allowed and nothing is due until a test
   says otherwise */
record TestAuthority(LongFunction<List<Mint>> schedule, MintRule mints, Predicate<Claim> claims,
	Predicate<Transfer> transfers) implements LedgerAuthority {
	/* The question a mint is asked, with the supply of the state it would be made on */
	interface MintRule {
		boolean allows(Mint mint, long time, long supply);
	}

	static TestAuthority open() {
		return new TestAuthority(time -> List.of(), (mint, time, supply) -> true, claim -> true, transfer -> true);
	}

	TestAuthority withSchedule(LongFunction<List<Mint>> other) {
		return new TestAuthority(other, mints, claims, transfers);
	}

	TestAuthority withMints(BiPredicate<Mint, Long> other) {
		return withMintsOnSupply((mint, time, supply) -> other.test(mint, time));
	}

	TestAuthority withMintsOnSupply(MintRule other) {
		return new TestAuthority(schedule, other, claims, transfers);
	}

	TestAuthority withClaims(Predicate<Claim> other) {
		return new TestAuthority(schedule, mints, other, transfers);
	}

	TestAuthority withTransfers(Predicate<Transfer> other) {
		return new TestAuthority(schedule, mints, claims, other);
	}

	@Override
	public List<Mint> due(long time) {
		return schedule.apply(time);
	}

	@Override
	public boolean authorizes(Mint mint, long time, long supply) {
		return mints.allows(mint, time, supply);
	}

	@Override
	public boolean authorizes(Claim claim) {
		return claims.test(claim);
	}

	@Override
	public boolean authorizes(Transfer transfer) {
		return transfers.test(transfer);
	}
}
