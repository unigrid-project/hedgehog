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
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;

/**
* Sums what the mint storage spork promises an address but the chain has not minted yet: every mint
* whose height lies above the current height. A mint at or below it is already part of the chain's own
* balances.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PendingMints {
	public static BigDecimal amountFor(String address, MintStorage mintStorage, int currentHeight) {
		final byte[] addressHash = LegacyAddress.decode(address);
		final Map<Location, BigDecimal> mints = Objects.isNull(mintStorage) ? Map.of()
			: mintStorage.<MintStorage.SporkData>getData().getMints();

		return mints.entrySet().stream()
			.filter(mint -> mint.getKey().getHeight() > currentHeight && isFor(addressHash, mint.getKey()))
			.map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add)
			.setScale(Coin.DECIMALS, RoundingMode.DOWN);
	}

	private static boolean isFor(byte[] addressHash, Location location) {
		try {
			return Arrays.equals(addressHash, LegacyAddress.decode(location.getAddress().getWif()));
		} catch (IllegalArgumentException ex) {
			log.atDebug().log("Skipped a mint for the undecodable address {}", location.getAddress().getWif());
			return false;
		}
	}
}
