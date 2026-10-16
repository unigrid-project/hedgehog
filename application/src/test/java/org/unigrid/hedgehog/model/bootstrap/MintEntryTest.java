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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.bitcoinj.base.Base58;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.MintSchedule;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.MintEntry.Kind;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class MintEntryTest {
	private static final int ACCOUNT_BYTES = 32;
	private static final int LEGACY_BYTES = 20;
	private static final long UNSIGNED_MAXIMUM = 4_294_967_295L;
	private static final long TIP_HEIGHT = 1_000;
	private static final long TIP_TIME = 1_700_000_000_000L;
	private static final MintSchedule SCHEDULE = new MintSchedule(TIP_HEIGHT, TIP_TIME);
	private static final BigDecimal COINS = BigDecimal.TEN;

	@Provide
	public Arbitrary<String> accounts() {
		return Arbitraries.bytes().array(byte[].class).ofSize(ACCOUNT_BYTES).map(HexFormat.of()::formatHex);
	}

	@Provide
	public Arbitrary<byte[]> hashes() {
		return Arbitraries.bytes().array(byte[].class).ofSize(LEGACY_BYTES);
	}

	private static Location location(String address, int number) {
		return Location.builder().address(new Address(address)).height(number).build();
	}

	private static Optional<MintEntry> entry(String address, int number) {
		return MintEntry.of(location(address, number), COINS);
	}

	private static MintEntry entryOf(String address, int number) {
		return entry(address, number).orElseThrow();
	}

	private static MintEntry withAmount(String amount) {
		return MintEntry.of(location("ab".repeat(ACCOUNT_BYTES), 5), new BigDecimal(amount)).orElseThrow();
	}

	private static String legacy(byte[] hash) {
		return LegacyAddress.encode(hash);
	}

	@Property
	public void readsSixtyFourLowerCaseHexCharactersAsAnAccountOfTheLedger(@ForAll("accounts") String account,
		@ForAll int number) {

		final MintEntry entry = entryOf(account, number);

		assertThat(entry.kind(), equalTo(Kind.NEW));
		assertThat(entry.recipient(), equalTo(AccountKey.fromHex(account)));
		assertThat(entry.amount(), equalTo(COINS));
	}

	@Property
	public void readsAnAddressOfTheOldChainAsALegacyAddress(@ForAll("hashes") byte[] hash, @ForAll int number) {

		final MintEntry entry = entryOf(legacy(hash), number);

		assertThat(entry.kind(), equalTo(Kind.OLD));
		assertThat(entry.recipient(), equalTo(new org.unigrid.hedgehog.ledger.LegacyAddress(hash)));
	}

	/* One spelling per account, so a lower-case entry and an upper-case one are never two grants to one key */
	@Property
	public void readsNoAccountInUpperCaseOrMixedCase(@ForAll("accounts") String account) {
		final String mixed = account.substring(0, ACCOUNT_BYTES) + account.substring(ACCOUNT_BYTES).toUpperCase();

		assertThat(entry(account.toUpperCase(), 7).isPresent(), is(account.equals(account.toUpperCase())));
		assertThat(entry(mixed, 7).isPresent(), is(account.equals(mixed)));
	}

	@Example
	public void readsNoAccountOfTheWrongLength() {
		final String account = "0f".repeat(ACCOUNT_BYTES);

		assertThat(entry(account, 7).isPresent(), is(true));
		assertThat(entry(account.substring(1), 7), equalTo(Optional.empty()));
		assertThat(entry(account + "0", 7), equalTo(Optional.empty()));
		assertThat(entry(account.substring(0, 2), 7), equalTo(Optional.empty()));
		assertThat(entry(account + account, 7), equalTo(Optional.empty()));
	}

	@Example
	public void readsNothingFromWhatIsNeitherAnAccountNorALegacyAddress() {
		final String legacy = legacy(new byte[LEGACY_BYTES]);
		final String tampered = legacy.substring(0, legacy.length() - 1) + (legacy.endsWith("2") ? "3" : "2");

		assertThat(entry("", 7), equalTo(Optional.empty()));
		assertThat(entry(" ", 7), equalTo(Optional.empty()));
		assertThat(entry("garbage", 7), equalTo(Optional.empty()));
		assertThat(entry("zz".repeat(ACCOUNT_BYTES), 7), equalTo(Optional.empty()));
		assertThat(entry(" " + "ab".repeat(ACCOUNT_BYTES), 7), equalTo(Optional.empty()));
		assertThat(entry("ab".repeat(ACCOUNT_BYTES) + "\n", 7), equalTo(Optional.empty()));
		assertThat(entry("0x" + "ab".repeat(ACCOUNT_BYTES - 1), 7), equalTo(Optional.empty()));
		assertThat(entry(tampered, 7), equalTo(Optional.empty()));
		assertThat(entry(legacy + legacy, 7), equalTo(Optional.empty()));
		assertThat(entry(legacy, 7).isPresent(), is(true));
	}

	@Example
	public void readsNothingFromAnAddressOfAnotherNetwork() {
		final String otherNetwork = Base58.encodeChecked(LegacyAddress.PUBLIC_KEY_VERSION + 1, new byte[LEGACY_BYTES]);

		assertThat(entry(otherNetwork, 7), equalTo(Optional.empty()));
	}

	@Example
	public void readsNothingWhenSomethingIsMissing() {
		final Location noAddress = Location.builder().height(7).build();
		final Location noWif = Location.builder().address(new Address()).height(7).build();

		assertThat(MintEntry.of(noAddress, COINS), equalTo(Optional.empty()));
		assertThat(MintEntry.of(noWif, COINS), equalTo(Optional.empty()));
		assertThat(MintEntry.of(location("ab".repeat(ACCOUNT_BYTES), 7), null), equalTo(Optional.empty()));
	}

	@Property
	public void tellsAnAccountFromTheAddressesTheOldChainHad(@ForAll("accounts") String account,
		@ForAll("hashes") byte[] hash) {

		assertThat(MintEntry.isAccount(account), is(true));
		assertThat(MintEntry.isAccount(account.toUpperCase()), is(account.equals(account.toUpperCase())));
		assertThat(MintEntry.isAccount(legacy(hash)), is(false));
		assertThat(MintEntry.isAccount("garbage"), is(false));
		assertThat(MintEntry.isAccount(null), is(false));
	}

	@Example
	public void countsTheNumberUnsignedWhateverTheModelHolds() {
		final String account = "ab".repeat(ACCOUNT_BYTES);

		assertThat(entryOf(account, 0).number(), equalTo(0L));
		assertThat(entryOf(account, Integer.MAX_VALUE).number(), equalTo(2_147_483_647L));
		assertThat(entryOf(account, Integer.MIN_VALUE).number(), equalTo(2_147_483_648L));
		assertThat(entryOf(account, -1).number(), equalTo(UNSIGNED_MAXIMUM));
		assertThat(entryOf(account, (int) 4_000_000_000L).number(), equalTo(4_000_000_000L));
	}

	@Example
	public void refusesANumberThatIsNotOfThirtyTwoBits() {
		final AccountKey key = AccountKey.fromHex("ab".repeat(ACCOUNT_BYTES));

		assertThrows(IllegalArgumentException.class, () -> new MintEntry(key, -1, COINS));
		assertThrows(IllegalArgumentException.class, () -> new MintEntry(key, UNSIGNED_MAXIMUM + 1, COINS));
		assertThat(new MintEntry(key, UNSIGNED_MAXIMUM, COINS).number(), equalTo(UNSIGNED_MAXIMUM));
	}

	@Example
	public void isDueAtTheTimeOfAnAccountEntryWhateverTheTipIs() {
		final String account = "ab".repeat(ACCOUNT_BYTES);

		assertThat(entryOf(account, 1_900_000_000).dueMillis(SCHEDULE), equalTo(OptionalLong.of(1_900_000_000_000L)));
		assertThat(entryOf(account, 0).dueMillis(SCHEDULE), equalTo(OptionalLong.of(0)));
		assertThat(entryOf(account, (int) TIP_HEIGHT).dueMillis(SCHEDULE), equalTo(OptionalLong.of(1_000_000L)));
		assertThat(entryOf(account, 5).dueMillis(SCHEDULE), equalTo(OptionalLong.of(5_000L)));
		assertThat(entryOf(account, 1_900_000_000).dueMillis(new MintSchedule(Integer.MAX_VALUE, 0)),
			equalTo(OptionalLong.of(1_900_000_000_000L)));
	}

	@Example
	public void isDueAtTheEdgesOfTheThirtyTwoBitsOfAnAccountEntry() {
		final String account = "ab".repeat(ACCOUNT_BYTES);

		assertThat(entryOf(account, Integer.MAX_VALUE).dueMillis(SCHEDULE), equalTo(OptionalLong.of(2_147_483_647_000L)));
		assertThat(entryOf(account, Integer.MIN_VALUE).dueMillis(SCHEDULE), equalTo(OptionalLong.of(2_147_483_648_000L)));
		assertThat(entryOf(account, -1).dueMillis(SCHEDULE), equalTo(OptionalLong.of(UNSIGNED_MAXIMUM * 1_000)));
	}

	@Property
	public void isDueAtTheTimeOfTheNumberForAnAccountAndAtTheScheduleForTheOldChain(@ForAll("accounts") String account,
		@ForAll("hashes") byte[] hash, @ForAll int number) {

		final long unsigned = Integer.toUnsignedLong(number);
		final OptionalLong old = entryOf(legacy(hash), number).dueMillis(SCHEDULE);

		assertThat(entryOf(account, number).dueMillis(SCHEDULE), equalTo(OptionalLong.of(unsigned * 1_000)));
		assertThat(old, equalTo(unsigned > TIP_HEIGHT ? OptionalLong.of(SCHEDULE.dueTime(unsigned))
			: OptionalLong.empty()));
	}

	@Example
	public void neverMintsAnOldEntryAtOrBelowTheSnapshotTipAndMintsAboveItAtTheSchedule() {
		final String old = legacy(new byte[LEGACY_BYTES]);

		assertThat(entryOf(old, (int) TIP_HEIGHT).dueMillis(SCHEDULE), equalTo(OptionalLong.empty()));
		assertThat(entryOf(old, (int) TIP_HEIGHT - 1).dueMillis(SCHEDULE), equalTo(OptionalLong.empty()));
		assertThat(entryOf(old, 0).dueMillis(SCHEDULE), equalTo(OptionalLong.empty()));
		assertThat(entryOf(old, (int) TIP_HEIGHT + 1).dueMillis(SCHEDULE),
			equalTo(OptionalLong.of(TIP_TIME + MintSchedule.INTERVAL)));
		assertThat(entryOf(old, (int) TIP_HEIGHT + 100).dueMillis(SCHEDULE),
			equalTo(OptionalLong.of(TIP_TIME + 100 * MintSchedule.INTERVAL)));
		assertThat(entryOf(old, -1).dueMillis(SCHEDULE),
			equalTo(OptionalLong.of(SCHEDULE.dueTime(UNSIGNED_MAXIMUM))));
	}

	@Example
	public void doesNotReadTheNumberOfAnOldEntryAsSeconds() {
		final String old = legacy(new byte[LEGACY_BYTES]);
		final int height = 1_900_000_000;

		assertThat(entryOf(old, height).dueMillis(SCHEDULE),
			not(equalTo(OptionalLong.of(Integer.toUnsignedLong(height) * 1_000))));
	}

	@Property
	public void makesOneReferenceOfTheAddressAndTheNumber(@ForAll("accounts") String account,
		@ForAll("hashes") byte[] hash, @ForAll int number) {

		for (String address : new String[] { account, legacy(hash) }) {
			final MintEntry entry = entryOf(address, number);

			assertThat(entry.reference(), equalTo(entryOf(address, number).reference()));
			assertThat(entry.reference(), equalTo(Reference.ofSpork(entry.recipient(), entry.number())));
			assertThat(entryOf(address, number + 1).reference(), not(equalTo(entry.reference())));
		}
	}

	@Example
	public void makesTheReferenceOfTheUnsignedNumber() {
		final String account = "ab".repeat(ACCOUNT_BYTES);
		final MintEntry entry = entryOf(account, -1);

		assertThat(entry.reference(), equalTo(Reference.ofSpork(AccountKey.fromHex(account), UNSIGNED_MAXIMUM)));
		assertThat(entry.reference(), not(equalTo(Reference.ofSpork(AccountKey.fromHex(account), -1))));
	}

	@Property
	public void doesNotLetTheAmountChangeTheReference(@ForAll("accounts") String account, @ForAll int number) {

		final MintEntry small = MintEntry.of(location(account, number), BigDecimal.ONE).orElseThrow();
		final MintEntry large = MintEntry.of(location(account, number), BigDecimal.TEN).orElseThrow();

		assertThat(small.reference(), equalTo(large.reference()));
	}

	@Example
	public void countsUnitsOfEightDecimalsAndCutsWhatIsFiner() {
		assertThat(withAmount("0.123456789").units(), equalTo(OptionalLong.of(12_345_678)));
		assertThat(withAmount("0.00000001").units(), equalTo(OptionalLong.of(1)));
		assertThat(withAmount("0.000000019").units(), equalTo(OptionalLong.of(1)));
		assertThat(withAmount("1").units(), equalTo(OptionalLong.of(100_000_000)));
		assertThat(withAmount("5.00000000").units(), equalTo(OptionalLong.of(500_000_000)));
		assertThat(withAmount("1E+2").units(), equalTo(OptionalLong.of(10_000_000_000L)));
		assertThat(withAmount("220000").units(), equalTo(OptionalLong.of(22_000_000_000_000L)));
	}

	@Property
	public void countsEveryAmountOfALongExactly(@ForAll long units) {
		final OptionalLong expected = units > 0 ? OptionalLong.of(units) : OptionalLong.empty();

		assertThat(withAmount(BigDecimal.valueOf(units, 8).toPlainString()).units(), equalTo(expected));
		assertThat(withAmount(BigDecimal.valueOf(units, 8).toString()).units(), equalTo(expected));
		assertThat(withAmount(BigDecimal.valueOf(units, 10).multiply(BigDecimal.valueOf(100)).toString()).units(),
			equalTo(expected));
	}

	@Example
	public void fitsTheLargestAmountOfALongAndNotOneUnitMore() {
		assertThat(withAmount("92233720368.54775807").units(), equalTo(OptionalLong.of(Long.MAX_VALUE)));
		assertThat(withAmount("92233720368.54775807999").units(), equalTo(OptionalLong.of(Long.MAX_VALUE)));
		assertThat(withAmount("92233720368.54775808").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("92233720369").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("100000000000").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("1E+30").units(), equalTo(OptionalLong.empty()));
	}

	@Example
	public void countsNoUnitsOfNothingOrOfLessThanOne() {
		assertThat(withAmount("0").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("0.00000000").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("0.000000009").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("-1").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("-0.00000001").units(), equalTo(OptionalLong.empty()));
		assertThat(withAmount("-92233720368.54775808").units(), equalTo(OptionalLong.empty()));
	}

	@Example
	public void countsNoUnitsOfAnAbsurdExponentWithoutExpandingIt() {
		final List<String> amounts = List.of("1E+1000000000", "1E-1000000000", "-1E+1000000000", "9E+2147483647",
			"9E-2147483647", "1E+300000000", "1E-300000000", "7E+100000000", "7E-100000000");

		/* Expanding such an exponent in full takes minutes, so it is waited for only for so long */
		final List<OptionalLong> counted = await().atMost(Duration.ofSeconds(10))
			.until(() -> amounts.stream().map(amount -> withAmount(amount).units()).toList(), notNullValue());

		assertThat(counted, everyItem(equalTo(OptionalLong.empty())));
		assertThat(counted.size(), equalTo(amounts.size()));
	}

	@Provide
	public Arbitrary<BigDecimal> coins() {
		final BigDecimal limit = new BigDecimal("92233720369");

		return Arbitraries.integers().between(0, 12).flatMap(scale -> Arbitraries.bigDecimals()
			.between(BigDecimal.valueOf(-5), limit).ofScale(scale));
	}

	@Property
	public void countsTheUnitsOfAnAmountCutAtEightDecimalsAndKeepsAWayBeyondALongBeyondIt(
		@ForAll("coins") BigDecimal coins) {

		final BigInteger cut = coins.movePointRight(8).setScale(0, RoundingMode.DOWN).toBigInteger();
		final BigInteger counted = MintEntry.unitsOf(coins);

		if (cut.signum() <= 0) {
			assertThat(counted, equalTo(BigInteger.ZERO));
		} else if (cut.bitLength() < Long.SIZE) {
			assertThat(counted, equalTo(cut));
		} else {
			assertThat(counted.bitLength(), greaterThan(Long.SIZE - 1));
		}
	}

	@Example
	public void countsTheUnitsAtTheEdgesOfALongWithoutExpandingAnExponent() {
		assertThat(MintEntry.unitsOf(new BigDecimal("92233720368.54775807")), equalTo(BigInteger.valueOf(Long.MAX_VALUE)));
		assertThat(MintEntry.unitsOf(new BigDecimal("92233720368.54775808")).bitLength(), equalTo(Long.SIZE));
		assertThat(MintEntry.unitsOf(new BigDecimal("1E+1000000000")).bitLength(), equalTo(Long.SIZE));
		assertThat(MintEntry.unitsOf(new BigDecimal("-1E+1000000000")), equalTo(BigInteger.ZERO));
		assertThat(MintEntry.unitsOf(new BigDecimal("1E-1000000000")), equalTo(BigInteger.ZERO));
		assertThat(MintEntry.unitsOf(new BigDecimal("0.000000009")), equalTo(BigInteger.ZERO));
	}

	@Example
	public void explainsThatAnOldStyleAddressTakesAHeight() {
		final String message = assertThrows(IllegalArgumentException.class,
			() -> MintEntry.heightOf(legacy(new byte[LEGACY_BYTES]), 2_147_483_648L)).getMessage();

		assertThat(message, containsString("old-style"));
		assertThat(message, containsString("height"));
		assertThat(message, containsString("2147483648"));
	}

	@Example
	public void keepsTheThirtyTwoBitsOfAnAccountNumberAsTheModelHoldsThem() {
		final String account = "ab".repeat(ACCOUNT_BYTES);

		assertThat(MintEntry.heightOf(account, 0), equalTo(0));
		assertThat(MintEntry.heightOf(account, 1_900_000_000L), equalTo(1_900_000_000));
		assertThat(MintEntry.heightOf(account, 2_147_483_648L), equalTo(Integer.MIN_VALUE));
		assertThat(MintEntry.heightOf(account, 4_000_000_000L), equalTo((int) 4_000_000_000L));
		assertThat(MintEntry.heightOf(account, UNSIGNED_MAXIMUM), equalTo(-1));
	}

	@Example
	public void refusesAnAccountNumberThatIsNotATimeOfThirtyTwoBits() {
		final String account = "ab".repeat(ACCOUNT_BYTES);

		for (long number : new long[] { -1, UNSIGNED_MAXIMUM + 1, Long.MAX_VALUE, Long.MIN_VALUE }) {
			final String message = assertThrows(IllegalArgumentException.class,
				() -> MintEntry.heightOf(account, number)).getMessage();

			assertThat(message, containsString("ledger account"));
			assertThat(message, containsString("4294967295"));
			assertThat(message, containsString(Long.toString(number)));
		}
	}

	@Property
	public void takesOnlyAHeightOfTheOldChainForEveryOtherAddress(@ForAll("hashes") byte[] hash) {
		for (String address : new String[] { legacy(hash), "garbage", "AB".repeat(ACCOUNT_BYTES), "" }) {
			assertThat(MintEntry.heightOf(address, 0), equalTo(0));
			assertThat(MintEntry.heightOf(address, 4_200_000L), equalTo(4_200_000));
			assertThat(MintEntry.heightOf(address, Integer.MAX_VALUE), equalTo(Integer.MAX_VALUE));
			assertThrows(IllegalArgumentException.class, () -> MintEntry.heightOf(address, 2_147_483_648L));
			assertThrows(IllegalArgumentException.class, () -> MintEntry.heightOf(address, UNSIGNED_MAXIMUM));
			assertThrows(IllegalArgumentException.class, () -> MintEntry.heightOf(address, Long.MAX_VALUE));
		}
	}
}
