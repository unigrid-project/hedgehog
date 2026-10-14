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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerClaimTest {
	private static final LegacyWallet WALLET = new LegacyWallet(1, true);
	private static final LegacyAddress LEGACY = WALLET.address();
	private static final long OWED = 500;

	private static LedgerState claimState(MintAuthority authority) {
		return soloSnapshotState(authority, Map.of(LEGACY, OWED));
	}

	private static Claim claim(LedgerState state, AccountKey recipient) {
		return WALLET.claim(recipient, state.chainId());
	}

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	@Example
	public void shouldMoveTheSnapshotBalanceToTheRecipientWithoutGrowingTheSupply() {
		final LedgerState state = claimState(ANY_MINT);

		state.apply(soloBlock(state, claim(state, account(1))));

		assertThat(state.balanceOf(account(1)), equalTo(OWED));
		assertThat(state.balanceOf(LEGACY), equalTo(0L));
		assertThat(state.supply(), equalTo(OWED));
		assertThat(state.unclaimed(), equalTo(0L));
		assertThat(state.hasMinted(Reference.ofClaim(SNAPSHOT_HASH, LEGACY)), is(true));
	}

	@Example
	public void shouldRefuseASecondClaimOfTheSameAddress() {
		final LedgerState state = claimState(ANY_MINT);

		state.apply(soloBlock(state, claim(state, account(1))));

		assertThat(reasonOf(state.rejectionOf(claim(state, account(2)))), containsString("already claimed"));
	}

	@Example
	public void shouldRefuseTheSecondOfTwoClaimsInOneBlock() {
		final LedgerState state = claimState(ANY_MINT);
		final List<Transaction> both = List.of(claim(state, account(1)), claim(state, account(2)));

		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, both));
	}

	@Example
	public void shouldRefuseASignatureThatNamesAnotherRecipientOrChain() {
		final LedgerState state = claimState(ANY_MINT);
		final Claim signed = claim(state, account(1));
		final Claim moved = new Claim(signed.publicKey(), account(2), signed.signature());
		final Claim otherChain = WALLET.claim(account(1), new byte[Digests.HASH_SIZE]);

		assertThat(reasonOf(state.rejectionOf(moved)), containsString("signature"));
		assertThat(reasonOf(state.rejectionOf(otherChain)), containsString("signature"));
	}

	@Example
	public void shouldRefuseAKeyThatIsNotTheOneOfTheAddress() {
		final LedgerState state = claimState(ANY_MINT);
		final Claim signedByAnother = new LegacyWallet(2, true).claim(account(1), state.chainId());

		assertThat(reasonOf(state.rejectionOf(signedByAnother)), containsString("Nothing to claim"));
	}

	@Example
	public void shouldRefuseAnAddressWithNothingToClaim() {
		final LedgerState state = soloSnapshotState(ANY_MINT, Map.of());

		assertThat(reasonOf(state.rejectionOf(claim(state, account(1)))), containsString("Nothing to claim"));
	}

	@Example
	public void shouldRefuseABalanceAboveWhatTheSnapshotTotalHasLeft() {
		final LedgerState state = stateWithSnapshot(ANY_MINT, seeds(1, 1), 400, Map.of(LEGACY, OWED));

		assertThat(reasonOf(state.rejectionOf(claim(state, account(1)))), containsString("above"));
	}

	@Example
	public void shouldMoveWhatWasMintedToTheAddressBeforeTheClaim() {
		final LedgerState state = claimState(ANY_MINT);

		state.apply(soloBlock(state, new Mint(LEGACY, 30, reference(1))));
		state.apply(soloBlock(state, claim(state, account(1))));

		assertThat(state.balanceOf(account(1)), equalTo(OWED + 30));
		assertThat(state.balanceOf(LEGACY), equalTo(0L));
		assertThat(state.supply(), equalTo(OWED + 30));
	}

	@Example
	public void shouldPayAMintAfterTheClaimToTheRecipientAndGrowTheSupplyOnce() {
		final LedgerState state = claimState(ANY_MINT);

		state.apply(soloBlock(state, claim(state, account(1))));
		state.apply(soloBlock(state, new Mint(LEGACY, 20, reference(1))));

		assertThat(state.balanceOf(account(1)), equalTo(OWED + 20));
		assertThat(state.balanceOf(LEGACY), equalTo(0L));
		assertThat(state.supply(), equalTo(OWED + 20));
	}

	/* The blacklist is node-local policy: a validator holds to it before it signs, but a block that more than 2/3
	   signed stands for every node, or the nodes would disagree about the same chain */
	@Example
	public void shouldAskTheAuthorityAboutAClaimButTakeACertifiedOne() {
		final MintAuthority blacklist = new MintAuthority() {
			@Override
			public boolean authorizes(Mint mint) {
				return true;
			}

			@Override
			public boolean authorizes(Claim claim) {
				return false;
			}
		};
		final LedgerState signer = claimState(ANY_MINT);
		final Block certified = soloBlock(signer, claim(signer, account(1)));
		final LedgerState follower = claimState(blacklist);

		assertThat(reasonOf(follower.rejectionOf(claim(follower, account(1)))), containsString("not authorized"));
		assertThat(reasonOf(follower.proposalRejection(certified)), containsString("not authorized"));

		follower.apply(certified);

		assertThat(follower.balanceOf(account(1)), equalTo(OWED));
	}

	@Example
	public void shouldCommitToTheClaimAndTheUnclaimedInTheRoot() {
		final LedgerState state = claimState(ANY_MINT);
		final List<byte[]> leaves = new ArrayList<>();

		state.apply(soloBlock(state, claim(state, account(1))));
		leaves.add(Bytes.concat(new byte[] { 'H' }, Bytes.longBytes(state.height())));
		leaves.add(Bytes.concat(new byte[] { 'S' }, Bytes.longBytes(OWED)));
		leaves.add(Bytes.concat(new byte[] { 'U' }, Bytes.longBytes(0)));
		leaves.add(Bytes.concat(new byte[] { 'A' }, account(1).encode(), Bytes.longBytes(OWED)));
		leaves.add(Bytes.concat(new byte[] { 'R' }, LEGACY.encode(), account(1).encode()));
		leaves.add(Bytes.concat(new byte[] { 'M' }, Reference.ofClaim(SNAPSHOT_HASH, LEGACY).bytes()));
		state.validators().keys().forEach(key -> leaves.add(Bytes.concat(new byte[] { 'V' }, key.bytes())));
		state.previousValidators().keys().forEach(key -> leaves.add(Bytes.concat(new byte[] { 'Q' }, key.bytes())));

		assertThat(state.stateRoot(), equalTo(MerkleRoot.of(leaves)));
	}

	@Example
	public void shouldKeepOneWaitingClaimPerLegacyAddress() {
		final LedgerState state = claimState(ANY_MINT);
		final Mempool mempool = new Mempool();

		assertThat(mempool.admit(claim(state, account(1)), state), is(true));
		assertThat(mempool.admit(claim(state, account(2)), state), is(false));
	}
}
