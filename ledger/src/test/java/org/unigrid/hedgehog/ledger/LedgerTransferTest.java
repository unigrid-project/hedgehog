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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerTransferTest {
	private static final long FUNDS = 1000;

	private static LedgerState funded() {
		final LedgerState state = soloState(ANY_MINT);

		state.apply(soloBlock(state, mint(1, FUNDS)));
		return state;
	}

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	@Example
	public void shouldMoveTheAmountAndBankTheFee() {
		final LedgerState state = funded();

		state.apply(soloBlock(state, transfer(state, 1, account(2), 300, 10, 0)));

		assertThat(state.balanceOf(account(1)), equalTo(690L));
		assertThat(state.balanceOf(account(2)), equalTo(300L));
		assertThat(state.pool(), equalTo(10L));
		assertThat(state.nonceOf(account(1)), equalTo(1L));
		assertThat(state.balanceOf(account(1)) + state.balanceOf(account(2)) + state.pool() + state.unclaimed(),
			equalTo(state.supply()));
	}

	@Example
	public void shouldCarryTheNonceAndThePoolFromBlockToBlock() {
		final LedgerState state = funded();

		state.apply(soloBlock(state, transfer(state, 1, account(2), 300, 10, 0)));
		state.apply(soloBlock(state, transfer(state, 1, account(3), 200, 5, 1)));

		assertThat(state.nonceOf(account(1)), equalTo(2L));
		/* The second block paid the fee of the first to the one validator that signed it */
		assertThat(state.pool(), equalTo(5L));
		assertThat(state.balanceOf(key(1)), equalTo(10L));
		assertThat(state.balanceOf(account(1)), equalTo(FUNDS - 300 - 10 - 200 - 5));
		assertThat(state.balanceOf(account(2)), equalTo(300L));
		assertThat(state.balanceOf(account(3)), equalTo(200L));
	}

	@Example
	public void shouldLeaveTheStateAloneWhenALaterTransferOfTheBlockIsRefused() {
		final LedgerState state = funded();
		final byte[] root = state.stateRoot();
		final Transfer good = transfer(state, 1, account(2), 300, 10, 0);
		final Block block = withTransactions(state, soloBlock(state, good), good,
			transfer(state, 1, account(3), FUNDS, 1, 1));

		final InvalidBlockException refused = assertThrows(InvalidBlockException.class, () -> state.apply(block));

		assertThat(refused.getMessage(), containsString("does not cover"));
		assertThat(state.nonceOf(account(1)), equalTo(0L));
		assertThat(state.pool(), equalTo(0L));
		assertThat(state.balanceOf(account(1)), equalTo(FUNDS));
		assertThat(state.balanceOf(account(2)), equalTo(0L));
		assertThat(state.stateRoot(), equalTo(root));
	}

	@Example
	public void shouldRefuseAUsedNonceInALaterBlock() {
		final LedgerState state = funded();
		final Transfer first = transfer(state, 1, account(2), 300, 10, 0);

		state.apply(soloBlock(state, first));

		final Block replay = withTransactions(state, soloBlock(state, mint(2, 5)), first);
		final InvalidBlockException refused = assertThrows(InvalidBlockException.class, () -> state.apply(replay));

		assertThat(refused.getMessage(), containsString("next is"));
		assertThat(state.balanceOf(account(2)), equalTo(300L));
	}

	@Example
	public void shouldRefuseANonceThatWasUsed() {
		final LedgerState state = funded();
		final Transfer first = transfer(state, 1, account(2), 300, 10, 0);

		state.apply(soloBlock(state, first));

		assertThat(reasonOf(state.rejectionOf(first)), containsString("next is 1"));
		assertThat(reasonOf(state.rejectionOfQueued(first)), containsString("already used"));
	}

	@Example
	public void shouldRefuseTheSameNonceTwiceInOneBlock() {
		final LedgerState state = funded();
		final List<Transaction> both = List.of(transfer(state, 1, account(2), 100, 1, 0),
			transfer(state, 1, account(3), 100, 1, 0));

		assertThat(state.applicable(both).size(), equalTo(1));
	}

	@Example
	public void shouldRefuseAGapInTheNonceWhenAppliedButAcceptItQueued() {
		final LedgerState state = funded();
		final Transfer ahead = transfer(state, 1, account(2), 100, 1, 1);

		assertThat(reasonOf(state.rejectionOf(ahead)), containsString("next is 0"));
		assertThat(state.rejectionOfQueued(ahead).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseWhatTheBalanceDoesNotCover() {
		final LedgerState state = funded();

		assertThat(reasonOf(state.rejectionOf(transfer(state, 1, account(2), 995, 10, 0))),
			containsString("does not cover"));
		assertThat(state.rejectionOf(transfer(state, 1, account(2), 990, 10, 0)).isPresent(), is(false));
		assertThat(reasonOf(state.rejectionOf(transfer(state, 3, account(2), 1, 0, 0))),
			containsString("does not cover"));
	}

	@Example
	public void shouldNeverLetAmountPlusFeeWrap() {
		final LedgerState state = funded();

		assertThat(reasonOf(state.rejectionOf(transfer(state, 1, account(2), Long.MAX_VALUE - 5, 10, 0))),
			containsString("overflow"));
		assertThat(reasonOf(state.rejectionOf(transfer(state, 1, account(2), Long.MAX_VALUE, Long.MAX_VALUE, 0))),
			containsString("overflow"));
	}

	@Example
	public void shouldRefuseATamperedTransferAndOneSignedForAnotherChain() {
		final LedgerState state = funded();
		final Transfer signed = transfer(state, 1, account(2), 100, 1, 0);
		final Transfer tampered = new Transfer(signed.sender(), signed.recipient(), 200, 1, 0, signed.signature());
		final Transfer otherChain = Transfer.signed(accountSeed(1), account(2), 100, 1, 0, new byte[Digests.HASH_SIZE]);

		assertThat(reasonOf(state.rejectionOf(tampered)), containsString("signature"));
		assertThat(reasonOf(state.rejectionOf(otherChain)), containsString("signature"));
	}

	@Example
	public void shouldNotTakeAForgedSignatureForOneThatWasChecked() {
		final LedgerState state = funded();
		final Transfer valid = transfer(state, 1, account(2), 100, 1, 0);
		final Transfer forged = new Transfer(valid.sender(), valid.recipient(), valid.amount(), valid.fee(),
			valid.nonce(), new byte[Ed25519.SIGNATURE_SIZE]);

		assertThat(state.rejectionOf(valid).isPresent(), is(false));
		assertThat(reasonOf(state.rejectionOf(forged)), containsString("signature"));
	}

	@Example
	public void shouldLetAnAccountPayItselfAndCostOnlyTheFee() {
		final LedgerState state = funded();

		state.apply(soloBlock(state, transfer(state, 1, account(1), 300, 10, 0)));

		assertThat(state.balanceOf(account(1)), equalTo(FUNDS - 10));
		assertThat(state.pool(), equalTo(10L));
	}

	@Example
	public void shouldPayAClaimedLegacyAddressToTheAccountThatClaimedIt() {
		final LegacyWallet wallet = new LegacyWallet(1, true);
		final LedgerState state = soloSnapshotState(ANY_MINT, Map.of(wallet.address(), 500L));

		state.apply(soloBlock(state, wallet.claim(account(5), state.chainId())));
		state.apply(soloBlock(state, mint(1, FUNDS)));
		state.apply(soloBlock(state, transfer(state, 1, wallet.address(), 100, 1, 0)));

		assertThat(state.balanceOf(account(5)), equalTo(600L));
		assertThat(state.balanceOf(wallet.address()), equalTo(0L));
	}

	@Example
	public void shouldCommitToTheNonceAndThePoolInTheRoot() {
		final LedgerState state = funded();
		final Map<AccountKey, Long> nonces = Map.of(account(1), 1L, account(2), 0L);
		final List<byte[]> leaves = new ArrayList<>();

		state.apply(soloBlock(state, transfer(state, 1, account(2), 300, 10, 0)));
		leaves.add(Bytes.concat(new byte[] { 'H' }, Bytes.longBytes(state.height())));
		leaves.add(Bytes.concat(new byte[] { 'S' }, Bytes.longBytes(FUNDS)));
		leaves.add(Bytes.concat(new byte[] { 'U' }, Bytes.longBytes(0)));
		leaves.add(Bytes.concat(new byte[] { 'P' }, Bytes.longBytes(10)));
		Stream.of(account(1), account(2)).sorted().forEach(account -> leaves.add(Bytes.concat(new byte[] { 'A' },
			account.encode(), Bytes.longBytes(state.balanceOf(account)), Bytes.longBytes(nonces.get(account)))));
		leaves.add(Bytes.concat(new byte[] { 'M' }, reference(1).bytes()));
		state.validators().keys().forEach(key -> leaves.add(Bytes.concat(new byte[] { 'V' }, key.bytes())));
		state.previousValidators().keys().forEach(key -> leaves.add(Bytes.concat(new byte[] { 'Q' }, key.bytes())));

		assertThat(state.stateRoot(), equalTo(MerkleRoot.of(leaves)));
	}
}
