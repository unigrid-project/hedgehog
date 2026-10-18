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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import java.util.HexFormat;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import org.bitcoinj.base.Base58;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Address;

/* The two ways a person writes who gets coins and how many: a ledger account as 64 lower-case hex characters or a
   legacy address as base58, and coins as plain decimal text of at most eight decimals */
public class LedgerTextTest {
	private static final int ACCOUNT_BYTES = 32;
	private static final int LEGACY_BYTES = 20;
	private static final int OTHER_NETWORK_VERSION = 41;
	private static final String ZERO_HASH_ADDRESS = "H6X8PLvXQDY3iLaTynKkQ1tUBBJjSZSf23";
	private static final String BITCOIN_ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";
	private static final String LARGEST_COINS = "92233720368.54775807";

	private static final String EMPTY = "is empty";
	private static final String NOT_PLAIN = "is plain digits";
	private static final String TOO_MANY_DECIMALS = "at most 8 decimals";
	private static final String NOT_ABOVE_ZERO = "is above zero";
	private static final String TOO_LARGE = "is at most " + LARGEST_COINS;

	@Provide
	public Arbitrary<String> accounts() {
		return Arbitraries.bytes().array(byte[].class).ofSize(ACCOUNT_BYTES).map(HexFormat.of()::formatHex);
	}

	@Provide
	public Arbitrary<String> legacyAddresses() {
		return Arbitraries.bytes().array(byte[].class).ofSize(LEGACY_BYTES).map(LegacyAddress::encode);
	}

	private static void assertRefused(final String coins, final String reason) {
		final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> LedgerText.units(coins));

		assertThat(refused.getMessage(), containsString(reason));
	}

	@Property(tries = 200)
	public void shouldReadAnAccountAndWriteItBackAsTheSameText(@ForAll("accounts") final String account) {
		final Optional<Address> address = LedgerText.address(account);

		assertThat(address, equalTo(Optional.of(AccountKey.fromHex(account))));
		assertThat(LedgerText.text(address.orElseThrow()), equalTo(account));
	}

	@Property(tries = 200)
	public void shouldReadALegacyAddressAndWriteItBackAsTheSameText(
		@ForAll("legacyAddresses") final String legacy) {
		final Optional<Address> address = LedgerText.address(legacy);

		assertThat(address.orElseThrow() instanceof org.unigrid.hedgehog.ledger.LegacyAddress, is(true));
		assertThat(address.orElseThrow().bytes(), equalTo(LegacyAddress.decode(legacy)));
		assertThat(LedgerText.text(address.orElseThrow()), equalTo(legacy));
	}

	@Property(tries = 200)
	public void shouldTellAnAccountFromTheAddressesTheOldChainHad(@ForAll("accounts") final String account,
		@ForAll("legacyAddresses") final String legacy) {

		assertThat(LedgerText.isAccount(account), is(true));
		assertThat(LedgerText.isAccount(account.toUpperCase()), is(account.equals(account.toUpperCase())));
		assertThat(LedgerText.isAccount(legacy), is(false));
		assertThat(LedgerText.isAccount("garbage"), is(false));
		assertThat(LedgerText.isAccount(null), is(false));
	}

	@Example
	public void shouldNotTakeAnAccountInUpperCaseOrOfAnotherWidth() {
		final String account = "ab".repeat(ACCOUNT_BYTES);

		assertThat(LedgerText.address(account).isPresent(), is(true));
		assertThat(LedgerText.address(account.toUpperCase()), equalTo(Optional.empty()));
		assertThat(LedgerText.address(account.substring(1)), equalTo(Optional.empty()));
		assertThat(LedgerText.address(account + "a"), equalTo(Optional.empty()));
	}

	@Example
	public void shouldNotTakeALegacyAddressThatTheChecksumOrTheVersionOrTheWidthRefuses() {
		final String withBadChecksum = ZERO_HASH_ADDRESS.substring(0, ZERO_HASH_ADDRESS.length() - 1) + "4";
		final String ofAnotherVersion = Base58.encodeChecked(OTHER_NETWORK_VERSION, new byte[LEGACY_BYTES]);
		final String ofAnotherWidth = Base58.encodeChecked(LegacyAddress.PUBLIC_KEY_VERSION,
			new byte[LEGACY_BYTES - 1]);

		assertThat(LedgerText.address(ZERO_HASH_ADDRESS).isPresent(), is(true));
		assertThat(LedgerText.address(withBadChecksum), equalTo(Optional.empty()));
		assertThat(LedgerText.address(ofAnotherVersion), equalTo(Optional.empty()));
		assertThat(LedgerText.address(ofAnotherWidth), equalTo(Optional.empty()));
		assertThat(LedgerText.address(BITCOIN_ADDRESS), equalTo(Optional.empty()));
	}

	@Example
	public void shouldNotTakeNothingOrSpaceAroundAnAddress() {
		assertThat(LedgerText.address(""), equalTo(Optional.empty()));
		assertThat(LedgerText.address(" "), equalTo(Optional.empty()));
		assertThat(LedgerText.address(" " + ZERO_HASH_ADDRESS), equalTo(Optional.empty()));
		assertThat(LedgerText.address(ZERO_HASH_ADDRESS + "\n"), equalTo(Optional.empty()));
		assertThat(LedgerText.address(" " + "ab".repeat(ACCOUNT_BYTES)), equalTo(Optional.empty()));
	}

	@Example
	public void shouldCountCoinsInUnitsOfOneHundredMillionthOfACoin() {
		assertThat(LedgerText.units("1"), equalTo(100_000_000L));
		assertThat(LedgerText.units("0.00000001"), equalTo(1L));
		assertThat(LedgerText.units("12.5"), equalTo(1_250_000_000L));
		assertThat(LedgerText.units("100"), equalTo(10_000_000_000L));
		assertThat(LedgerText.units(LARGEST_COINS), equalTo(Long.MAX_VALUE));
	}

	@Example
	public void shouldTakeTrailingZerosBeyondTheEightDecimals() {
		assertThat(LedgerText.units("1.500000000"), equalTo(150_000_000L));
		assertThat(LedgerText.units("0.100000000000"), equalTo(10_000_000L));
	}

	@Example
	public void shouldRefuseAnEmptyAmount() {
		assertRefused("", EMPTY);
	}

	@Example
	public void shouldRefuseWhatIsNoPlainDecimal() {
		assertRefused("-1", NOT_PLAIN);
		assertRefused("+1", NOT_PLAIN);
		assertRefused("1e3", NOT_PLAIN);
		assertRefused("1E+3", NOT_PLAIN);
		assertRefused("1,5", NOT_PLAIN);
		assertRefused(" 1", NOT_PLAIN);
		assertRefused("1 ", NOT_PLAIN);
		assertRefused("1.", NOT_PLAIN);
		assertRefused(".5", NOT_PLAIN);
		assertRefused("0x10", NOT_PLAIN);
		assertRefused("١", NOT_PLAIN);
		assertRefused("1".repeat(49), NOT_PLAIN);
	}

	@Example
	public void shouldRefuseMoreThanEightDecimalsInsteadOfRoundingThem() {
		assertRefused("0.000000001", TOO_MANY_DECIMALS);
		assertRefused("12.345678901", TOO_MANY_DECIMALS);
		assertRefused("1.000000001", TOO_MANY_DECIMALS);
	}

	@Example
	public void shouldRefuseZero() {
		assertRefused("0", NOT_ABOVE_ZERO);
		assertRefused("0.0", NOT_ABOVE_ZERO);
		assertRefused("0.00000000", NOT_ABOVE_ZERO);
		assertRefused("0.000000000", NOT_ABOVE_ZERO);
	}

	@Example
	public void shouldRefuseWhatALongOfUnitsCannotHold() {
		assertRefused("92233720368.54775808", TOO_LARGE);
		assertRefused("92233720369", TOO_LARGE);
		assertRefused("1" + "0".repeat(20), TOO_LARGE);
	}

	@Example
	public void shouldWriteCoinsWithEightDecimalsAlways() {
		assertThat(LedgerText.coins(1), equalTo("0.00000001"));
		assertThat(LedgerText.coins(0), equalTo("0.00000000"));
		assertThat(LedgerText.coins(100_000_000L), equalTo("1.00000000"));
		assertThat(LedgerText.coins(Long.MAX_VALUE), equalTo(LARGEST_COINS));
	}

	@Property(tries = 200)
	public void shouldReadBackTheCoinsItWrote(@ForAll @LongRange(min = 1) final long units) {
		assertThat(LedgerText.units(LedgerText.coins(units)), equalTo(units));
	}
}
