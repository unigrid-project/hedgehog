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
import java.util.Arrays;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

public class PendingMintsTest {
	private static final int CURRENT_HEIGHT = 100;
	private static final String ADDRESS = LegacyAddress.encode(new byte[Hashing.ADDRESS_HASH_SIZE]);
	private static final String OTHER_ADDRESS = LegacyAddress.encode(filledHash((byte) 1));

	private final MintStorage mintStorage = new MintStorage();

	private static byte[] filledHash(byte value) {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		Arrays.fill(hash, value);
		return hash;
	}

	private void mint(String address, int height, String amount) {
		mintStorage.<MintStorage.SporkData>getData().getMints()
			.put(new Location(new Address(address), height), new BigDecimal(amount));
	}

	private BigDecimal pending() {
		return PendingMints.amountFor(ADDRESS, mintStorage, CURRENT_HEIGHT);
	}

	@Example
	public void shouldAddAMintAboveTheCurrentHeight() {
		mint(ADDRESS, CURRENT_HEIGHT + 1, "12.5");
		assertThat(pending(), equalTo(new BigDecimal("12.50000000")));
	}

	@Example
	public void shouldLeaveOutMintsTheChainHasReached() {
		mint(ADDRESS, CURRENT_HEIGHT, "1");
		mint(ADDRESS, CURRENT_HEIGHT - 1, "2");
		assertThat(pending(), equalTo(new BigDecimal("0E-8")));
	}

	@Example
	public void shouldSumMintsAtSeveralHeights() {
		mint(ADDRESS, CURRENT_HEIGHT + 1, "1.1");
		mint(ADDRESS, CURRENT_HEIGHT + 500, "2.2");
		assertThat(pending(), equalTo(new BigDecimal("3.30000000")));
	}

	@Example
	public void shouldLeaveOutMintsOfOtherAddresses() {
		mint(OTHER_ADDRESS, CURRENT_HEIGHT + 1, "5");
		assertThat(pending().signum(), is(0));
	}

	@Example
	public void shouldSkipMintsWhoseAddressDoesNotDecode() {
		mint("not an address", CURRENT_HEIGHT + 1, "5");
		mint(ADDRESS, CURRENT_HEIGHT + 1, "1");
		assertThat(pending(), equalTo(new BigDecimal("1.00000000")));
	}

	@Example
	public void shouldHaveNothingPendingWithoutMintStorage() {
		assertThat(PendingMints.amountFor(ADDRESS, null, CURRENT_HEIGHT).signum(), is(0));
	}

	@Example
	public void shouldKeepTheScaleOfCoins() {
		mint(ADDRESS, CURRENT_HEIGHT + 1, "7");
		assertThat(pending().scale(), is(Coin.DECIMALS));
	}
}
