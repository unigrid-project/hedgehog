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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* A refusal is marked as forged where a signature check fails, which is what a peer is charged for. Every
   transaction here would be taken as it was signed; the forgeries differ from it in the signature alone, the
   others are refused for anything but their signature. */
public class LedgerForgeryTest {
	private static final LegacyWallet WALLET = new LegacyWallet(1, true);
	private static final LegacyWallet OTHER = new LegacyWallet(2, true);
	private static final long OWED = 500;
	private static final long FUNDS = 1000;
	private static final AccountKey CANDIDATE = key(200);
	private static final int LAST_SIGNATURE_BIT = 8 * Ed25519.SIGNATURE_SIZE - 1;

	private static LedgerState funded() {
		final LedgerState state = soloSnapshotState(ANY_MINT, Map.of(WALLET.address(), OWED));

		state.apply(soloBlock(state, mint(1, FUNDS)));
		return state;
	}

	private static byte[] flipped(byte[] signature, int bit) {
		final byte[] flipped = signature.clone();

		flipped[bit / Byte.SIZE] ^= (byte) (1 << bit % Byte.SIZE);
		return flipped;
	}

	private static boolean forged(Optional<Rejection> rejection) {
		return rejection.filter(Rejection::forged).isPresent();
	}

	@Property(tries = 50)
	public void shouldCallARefusalForgedOnlyWhenTheSignatureDoesNotVerify(
		@ForAll @Size(Digests.HASH_SIZE) byte[] otherChain,
		@ForAll @IntRange(min = 0, max = LAST_SIGNATURE_BIT) int bit,
		@ForAll @LongRange(min = 1, max = FUNDS - 2) long amount) {

		final LedgerState state = funded();
		final byte[] chainId = state.chainId();

		Assume.that(!Arrays.equals(otherChain, chainId));

		final Vote vote = Vote.signed(seed(1), CANDIDATE, Vote.Action.ADD, state.round(), chainId);
		final Transfer transfer = transfer(state, 1, account(2), amount, 1, 0);
		final Claim claim = WALLET.claim(account(3), chainId);

		final List<Transaction> valid = List.of(vote, transfer, claim);

		/* A claim signature opens with the key-recovery header, which can differ for a valid one, so it is kept */
		final List<Transaction> forgeries = List.of(
			Vote.signed(seed(1), CANDIDATE, Vote.Action.ADD, state.round(), otherChain),
			new Vote(vote.voter(), CANDIDATE, vote.action(), vote.round(), flipped(vote.signature(), bit)),
			new Vote(vote.voter(), key(201), vote.action(), vote.round(), vote.signature()),
			Transfer.signed(accountSeed(1), account(2), amount, 1, 0, otherChain),
			new Transfer(transfer.sender(), transfer.recipient(), amount, 1, 0, flipped(transfer.signature(), bit)),
			new Transfer(transfer.sender(), transfer.recipient(), amount + 1, 1, 0, transfer.signature()),
			WALLET.claim(account(3), otherChain),
			new Claim(claim.publicKey(), account(3), flipped(claim.signature(), Byte.SIZE + bit)),
			new Claim(claim.publicKey(), account(4), claim.signature()));

		final List<Transaction> others = List.of(
			Vote.signed(seed(9), CANDIDATE, Vote.Action.ADD, state.round(), chainId),
			Vote.signed(seed(1), CANDIDATE, Vote.Action.ADD, state.round() + 1, chainId),
			Vote.signed(seed(1), key(1), Vote.Action.ADD, state.round(), chainId),
			Vote.signed(seed(1), key(1), Vote.Action.REMOVE, state.round(), chainId),
			transfer(state, 1, account(2), amount, 1, 1),
			transfer(state, 1, account(2), FUNDS + amount, 1, 0),
			transfer(state, 1, account(2), Long.MAX_VALUE - amount, amount + 1, 0),
			OTHER.claim(account(3), chainId),
			mint(1, amount),
			new Mint(account(5), Long.MAX_VALUE - amount, reference(5)));

		valid.forEach(transaction -> assertThat(state.rejectionOf(transaction), equalTo(Optional.empty())));
		forgeries.forEach(forgery -> assertThat(String.valueOf(forgery), forged(state.rejectionOf(forgery)),
			is(true)));
		others.forEach(other -> assertThat(String.valueOf(other), state.rejectionOf(other).isPresent()
			&& !forged(state.rejectionOf(other)), is(true)));
	}
}
