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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

public class SporkContentTest {
	private static final String OLD = LegacyAddress.encode(new byte[20]);
	private static final String ACCOUNT = "ab".repeat(32);
	private static final int HEIGHT = 4_200_000;

	private static MintStorage mintOf(String address, int height, String amount) {
		final MintStorage spork = new MintStorage();

		spork.<MintStorage.SporkData>getData().getMints().put(new Location(new Address(address), height),
			new BigDecimal(amount));
		return spork;
	}

	private static MintSupply supplyOf(String maxSupply) {
		final MintSupply spork = new MintSupply();

		spork.<MintSupply.SporkData>getData().setMaxSupply(new BigDecimal(maxSupply));
		return spork;
	}

	private static Vesting vesting() {
		return Vesting.builder().amount(BigDecimal.TEN).start(Instant.EPOCH).duration(Duration.ofDays(30)).parts(4)
			.build();
	}

	private static VestingStorage vestingOf(Vesting vesting) {
		final VestingStorage spork = new VestingStorage();

		spork.<VestingStorage.SporkData>getData().getVestingAddresses().put(new Address(OLD), vesting);
		return spork;
	}

	private static String refusal(GridSpork spork, GridSpork stored) {
		return assertThrows(IllegalArgumentException.class, () -> SporkContent.validate(spork, stored)).getMessage();
	}

	@Example
	public void shouldTakeAMintThatANodeCanMake() {
		for (String amount : List.of("0.00000001", "92233720368.54775807", "1", "1E+3", "1.500000000")) {
			SporkContent.validate(mintOf(ACCOUNT, HEIGHT, amount), null);
			SporkContent.validate(mintOf(OLD, HEIGHT, amount), null);
		}

		SporkContent.validate(mintOf(ACCOUNT, -1, "1"), null);
	}

	@Example
	public void shouldRefuseAMintThatNoNodeCanMake() {
		for (String amount : List.of("0", "-1", "0.000000009", "0.123456789", "92233720368.54775808", "1E+13")) {
			assertThat(amount, refusal(mintOf(ACCOUNT, HEIGHT, amount), null), containsString(ACCOUNT));
			assertThat(amount, refusal(mintOf(OLD, HEIGHT, amount), null), containsString(OLD));
		}

		for (String address : List.of(ACCOUNT.toUpperCase(), "garbage", ACCOUNT.substring(1))) {
			assertThat(refusal(mintOf(address, HEIGHT, "5"), null), containsString("neither"));
		}

		assertThat(refusal(mintOf(OLD, -1, "5"), null), containsString("height of the old chain"));
	}

	/* A version stored before a rule existed was taken then; only what a new version changes is judged */
	@Example
	public void shouldJudgeOnlyTheMintsThatTheNewVersionChanges() {
		final MintStorage stored = mintOf("garbage", HEIGHT, "5");
		final MintStorage next = mintOf("garbage", HEIGHT, "5");

		SporkContent.validate(next, stored);
		next.<MintStorage.SporkData>getData().getMints().put(new Location(new Address(OLD), HEIGHT), BigDecimal.ONE);
		SporkContent.validate(next, stored);

		next.<MintStorage.SporkData>getData().getMints().put(new Location(new Address("garbage"), HEIGHT),
			BigDecimal.TEN);
		assertThat(refusal(next, stored), containsString("garbage"));
	}

	@Example
	public void shouldTakeAMaxSupplyThatANodeCanHold() {
		for (String maxSupply : List.of("0", "1", "1000000", "999999999999.99999999")) {
			SporkContent.validate(supplyOf(maxSupply), null);
		}
	}

	@Example
	public void shouldRefuseAMaxSupplyThatNoNodeCanHold() {
		for (String maxSupply : List.of("-1", "0.123456789", "1E+13")) {
			assertThat(maxSupply, refusal(supplyOf(maxSupply), null), containsString("maximum supply"));
		}
	}

	@Example
	public void shouldTakeAVestingWithAnAmountAStartAndADuration() {
		SporkContent.validate(vestingOf(vesting()), null);
	}

	@Example
	public void shouldRefuseAVestingWithoutAnAmountWithinTheBoundsOrWithoutItsTime() {
		for (UnaryOperator<Vesting.VestingBuilder> damage : List.<UnaryOperator<Vesting.VestingBuilder>>of(
			vesting -> vesting.amount(null), vesting -> vesting.amount(new BigDecimal("0.123456789")),
			vesting -> vesting.amount(new BigDecimal("1E+13")), vesting -> vesting.start(null),
			vesting -> vesting.duration(null))) {

			assertThat(refusal(vestingOf(damage.apply(vesting().toBuilder()).build()), null), containsString(OLD));
		}
	}

	@Example
	public void shouldJudgeOnlyTheVestingsThatTheNewVersionChanges() {
		final Vesting unbounded = vesting().toBuilder().amount(null).build();

		SporkContent.validate(vestingOf(unbounded), vestingOf(unbounded));
		refusal(vestingOf(unbounded.toBuilder().parts(2).build()), vestingOf(unbounded));
	}

	@Provide
	public Arbitrary<StorageSpork.SporkData> sporkDataAcrossTheBounds() {
		return StorageSporkTest.sporkDataAcrossTheBounds();
	}

	@Property(tries = 50)
	public void shouldTakeTheStorageParametersThatTheSporkTakes(
		@ForAll("sporkDataAcrossTheBounds") StorageSpork.SporkData data) {

		final StorageSpork spork = new StorageSpork();

		spork.setData(data);

		if (StorageSporkTest.accepts(data)) {
			SporkContent.validate(spork, null);
		} else {
			refusal(spork, null);
		}
	}

	@Example
	public void shouldRefuseALedgerFeeOrBlacklistThatTheSporkRefuses() {
		final LedgerFee fee = new LedgerFee();
		final LedgerBlacklist blacklist = new LedgerBlacklist();

		SporkContent.validate(fee, null);
		SporkContent.validate(blacklist, null);

		fee.<LedgerFee.SporkData>getData().setMinimumTransferFee(new BigDecimal("1.00000001"));
		blacklist.setData(LedgerBlacklistTest.with(LedgerBlacklistTest.plus(LedgerBlacklistTest.distinctHashes(2),
			"not a hash")));

		assertThat(refusal(fee, null), containsString("minimumTransferFee"));
		assertThat(refusal(blacklist, null), containsString("legacyAddresses"));
	}

	@Example
	public void shouldRefuseASporkWithoutData() {
		final MintSupply spork = new MintSupply();

		spork.setData(null);
		refusal(spork, null);
	}
}
