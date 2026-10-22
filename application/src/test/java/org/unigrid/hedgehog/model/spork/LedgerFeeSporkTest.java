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

package org.unigrid.hedgehog.model.spork;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.model.spork.LedgerFee.SporkData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class LedgerFeeSporkTest {
	private static final int COIN_UNITS = 100_000_000;

	@Provide
	public static Arbitrary<BigDecimal> fees() {
		return Arbitraries.longs().between(0, COIN_UNITS).map(units -> BigDecimal.valueOf(units, 8));
	}

	@Provide
	public static Arbitrary<BigDecimal> feesAboveACoin() {
		return Arbitraries.longs().between(COIN_UNITS + 1L, Long.MAX_VALUE).map(units -> BigDecimal.valueOf(units, 8));
	}

	@Provide
	public static Arbitrary<BigDecimal> negativeFees() {
		return Arbitraries.longs().between(1, Long.MAX_VALUE).map(units -> BigDecimal.valueOf(-units, 8));
	}

	/* Below one coin, so the nine or more decimals are the only thing wrong with it */
	@Provide
	public static Arbitrary<BigDecimal> feesWithTooManyDecimals() {
		return Arbitraries.longs().between(0, COIN_UNITS / 10 - 1).flatMap(units ->
			Arbitraries.integers().between(1, 9).flatMap(digit -> Arbitraries.integers().between(9, 14)
				.map(scale -> BigDecimal.valueOf(units * 10 + digit, 9).setScale(scale)))
		);
	}

	public static SporkData with(BigDecimal fee) {
		final SporkData data = new SporkData();

		data.setMinimumTransferFee(fee);
		return data;
	}

	public static boolean accepts(SporkData data) {
		try {
			data.validate();
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Example
	public void startsAsFreeTransfersThatAreValid() {
		final SporkData data = new LedgerFee().getData();

		data.validate();
		assertThat(data.getMinimumTransferFee(), comparesEqualTo(BigDecimal.ZERO));
		assertThat(data.empty(), equalTo(data));
	}

	@Example
	public void isRegisteredAsAGovernedSporkType() {
		final GridSpork spork = GridSpork.create(GridSpork.Type.LEDGER_FEE);
		final short governed = GridSpork.Flag.GOVERNED.getValue();

		assertThat(GridSpork.Type.get((short) 1050), equalTo(GridSpork.Type.LEDGER_FEE));
		assertThat(spork, instanceOf(LedgerFee.class));
		assertThat(spork.getType(), equalTo(GridSpork.Type.LEDGER_FEE));
		assertThat(spork.getFlags() & governed, equalTo((int) governed));
	}

	@Example
	public void isKeptByTheSporkDatabaseInASlotOfItsOwn() {
		final SporkDatabase database = SporkDatabase.builder().build();
		final LedgerFee fee = new LedgerFee();

		assertThat(database.getLedgerFee(), nullValue());
		database.set(fee);
		assertThat(database.getLedgerFee(), sameInstance(fee));
		assertThat(database.get(GridSpork.Type.LEDGER_FEE), sameInstance(fee));
		assertThat(database.getLedgerBlacklist(), nullValue());
	}

	@Property
	public void acceptsAnyFeeUpToACoinInEightDecimals(@ForAll("fees") BigDecimal fee) {
		assertThat(accepts(with(fee)), is(true));
		assertThat(accepts(with(fee.stripTrailingZeros())), is(true));
	}

	@Example
	public void acceptsTheBoundsAndRefusesAnythingBeyond() {
		assertThat(accepts(with(BigDecimal.ZERO)), is(true));
		assertThat(accepts(with(new BigDecimal("0.00000001"))), is(true));
		assertThat(accepts(with(BigDecimal.ONE)), is(true));
		assertThat(accepts(with(new BigDecimal("1.00000000"))), is(true));
		assertThat(accepts(with(new BigDecimal("1.00000001"))), is(false));
		assertThat(accepts(with(new BigDecimal("-0.00000001"))), is(false));
		assertThat(accepts(with(new BigDecimal("0.000000001"))), is(false));
	}

	@Example
	public void takesExtraZerosAfterEightDecimalsForTheSameValue() {
		assertThat(accepts(with(new BigDecimal("0.100000000000"))), is(true));
		assertThat(accepts(with(new BigDecimal("0E-12"))), is(true));
		assertThat(accepts(with(new BigDecimal("1E+0"))), is(true));
	}

	@Property
	public void refusesANegativeFee(@ForAll("negativeFees") BigDecimal fee) {
		assertThat(accepts(with(fee)), is(false));
	}

	@Property
	public void refusesAFeeAboveACoin(@ForAll("feesAboveACoin") BigDecimal fee) {
		assertThat(accepts(with(fee)), is(false));
	}

	@Property
	public void refusesAFeeFinerThanEightDecimals(@ForAll("feesWithTooManyDecimals") BigDecimal fee) {
		assertThat(accepts(with(fee)), is(false));
	}

	@Example
	public void refusesAFeeOfAnAbsurdExponent() {
		assertThat(accepts(with(new BigDecimal("1E+1000000000"))), is(false));
		assertThat(accepts(with(new BigDecimal("1E-1000000000"))), is(false));
		assertThat(accepts(with(new BigDecimal("-1E+1000000000"))), is(false));
	}

	@Example
	public void refusesAMissingFee() {
		assertThat(accepts(with(null)), is(false));
	}

	@Property
	public void countsInUnitsOfEightDecimals(@ForAll("fees") BigDecimal fee) {
		assertThat(with(fee).units(), equalTo(fee.movePointRight(8).longValueExact()));
		assertThat(with(fee.stripTrailingZeros()).units(), equalTo(fee.movePointRight(8).longValueExact()));
	}

	@SneakyThrows
	@Example
	public void isFoundByDeductionFromItsOwnFieldAlone() {
		final ObjectMapper mapper = new ObjectMapper();
		final ChunkData read = mapper.readValue("{\"minimumTransferFee\":0.001}", ChunkData.class);

		assertThat(read, equalTo(with(new BigDecimal("0.001"))));

		final JsonNode written = mapper.valueToTree(with(new BigDecimal("0.001")));

		assertThat(written.fieldNames().next(), equalTo("minimumTransferFee"));
		assertThat(written.has("minimumNewAccountAmount"), is(true));
		assertThat(written.size(), is(2));
	}

	@SneakyThrows
	@Example
	public void leavesTheDeductionOfTheOtherSporkDataAlone() {
		final Map<String, Class<? extends ChunkData>> others = Map.of(
			"{\"maxSupply\":1}", MintSupply.SporkData.class,
			"{\"mints\":{}}", MintStorage.SporkData.class,
			"{\"vestingAddresses\":{}}", VestingStorage.SporkData.class,
			"{\"maxBytesPerNode\":1}", StorageSpork.SporkData.class,
			"{\"legacyAddresses\":[]}", LedgerBlacklist.SporkData.class
		);

		for (Map.Entry<String, Class<? extends ChunkData>> other : others.entrySet()) {
			assertThat(new ObjectMapper().readValue(other.getKey(), ChunkData.class).getClass(),
				equalTo(other.getValue()));
		}
	}
}
