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

package org.unigrid.hedgehog.service.ledger;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Digests;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LegacySignature;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;

public class LegacyWalletTest {
	private static final byte[] CHAIN = Digests.sha512("a chain".getBytes());
	private static final byte[] OTHER_CHAIN = Digests.sha512("another chain".getBytes());
	private static final AccountKey RECIPIENT = Ed25519.publicKey(LedgerTestChain.seed(31));
	private static final AccountKey OTHER_RECIPIENT = Ed25519.publicKey(LedgerTestChain.seed(32));

	@Property(tries = 20)
	public void shouldSignAClaimThatTheLedgerAccepts(@ForAll @IntRange(min = 1, max = 100_000) int secret,
		@ForAll boolean compressed) {

		final Claim claim = new LegacyWallet(secret, compressed).claim(RECIPIENT, CHAIN);

		assertThat(claim.hasValidSignature(CHAIN), is(true));
	}

	@Property(tries = 20)
	public void shouldSignOnlyForTheChainAndTheRecipientItWasMadeFor(
		@ForAll @IntRange(min = 1, max = 100_000) int secret, @ForAll boolean compressed) {

		final Claim claim = new LegacyWallet(secret, compressed).claim(RECIPIENT, CHAIN);
		final Claim redirected = new Claim(claim.publicKey(), OTHER_RECIPIENT, claim.signature());

		assertThat(claim.hasValidSignature(OTHER_CHAIN), is(false));
		assertThat(redirected.hasValidSignature(CHAIN), is(false));
	}

	@Property(tries = 40)
	public void shouldWriteTheRecoveryIdOfItsKeyIntoTheHeader(@ForAll @IntRange(min = 1, max = 100_000) int secret,
		@ForAll boolean compressed, @ForAll @AlphaChars @StringLength(min = 1, max = 100) String text) {

		final LegacyWallet wallet = new LegacyWallet(secret, compressed);

		assertThat(LegacySignature.recover(text, wallet.sign(text)).orElseThrow(), equalTo(wallet.publicKey()));
	}

	@Example
	public void shouldNotSignForAKeyOfAnotherWallet() {
		final Claim claim = new LegacyWallet(7, true).claim(RECIPIENT, CHAIN);
		final byte[] otherKey = new LegacyWallet(8, true).publicKey();

		assertThat(new Claim(otherKey, RECIPIENT, claim.signature()).hasValidSignature(CHAIN), is(false));
	}

	@Property(tries = 20)
	public void shouldNameTheHashOfItsKeyAsItsAddress(@ForAll @IntRange(min = 1, max = 100_000) int secret,
		@ForAll boolean compressed) {

		final LegacyWallet wallet = new LegacyWallet(secret, compressed);

		assertThat(wallet.address().bytes(), equalTo(Digests.hash160(wallet.publicKey())));
		assertThat(LegacyAddress.decode(wallet.base58()), equalTo(wallet.address().bytes()));
		assertThat(wallet.hash160Hex().length(), is(40));
	}

	@Example
	public void shouldHaveOtherAddressesForTheTwoEncodingsOfOneKey() {
		assertThat(new LegacyWallet(5, true).address(), is(not(equalTo(new LegacyWallet(5, false).address()))));
	}
}
