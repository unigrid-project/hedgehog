/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.network.chunk.ChunkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

public class GridSporkProvider {
	private ChunkData chunkData(GridSpork.Type gridSporkType) {
		final int size = RandomUtils.nextInt(0, 50);

		switch (gridSporkType) {
			case MINT_STORAGE: {
				final MintStorage.SporkData data = new MintStorage.SporkData();
				final Map<MintStorage.SporkData.Location, BigDecimal> mints = new HashMap<>();

				for (int i = 0; i < size; i++) {
					final Address address = Address.builder()
						.wif(RandomStringUtils.randomAlphanumeric(40)).build();

					final Location location = Location.builder()
						.address(address).height(RandomUtils.nextInt()).build();

					mints.put(location, BigDecimal.valueOf(RandomUtils.nextInt()));
				}

				data.setMints(mints);
				return data;

			} case MINT_SUPPLY: {
				final MintSupply.SporkData data = new MintSupply.SporkData();
				data.setMaxSupply(BigDecimal.valueOf(RandomUtils.nextInt()));
				return data;

			} case VESTING_STORAGE: {
				final VestingStorage.SporkData data = new VestingStorage.SporkData();
				final HashMap<Address, VestingStorage.SporkData.Vesting> vests = new HashMap<>();

				for (int i = 0; i < size; i++) {
					final Address address = Address.builder()
						.wif(RandomStringUtils.randomAlphanumeric(40)).build();

					final Vesting vesting = Vesting.builder()
						.start(Instant.ofEpochSecond(RandomUtils.nextInt()))
						.duration(Duration.ofSeconds(RandomUtils.nextInt()))
						.parts(RandomUtils.nextInt(5, 100))
						.amount(new BigDecimal(RandomUtils.nextInt()))
						.cliff(RandomUtils.nextInt(4, 40))
						.percent(RandomUtils.nextInt(1, 40))
						.block(RandomUtils.nextInt()).build();

					vests.put(address, vesting);
				}

				data.setVestingAddresses(vests);
				return data;
			} case STATISTICS_PUBKEY: {
				final StatisticsPubKey.SporkData data = new StatisticsPubKey.SporkData();
				final byte[] key = RandomUtils.nextBytes(140);

				data.setPublicKey(Hex.encodeHexString(key));
				return data;
			}
		}

		throw new IllegalArgumentException("Unsupported chunk type");
	}

	public Arbitrary<GridSpork> provide(GridSpork.Type gridSporkType, short flags, byte[] signature,
		Instant time, Instant previousTime) throws IllegalArgumentException {

		/* Limit amount of failures with "UNDEFINED" during data set generation by jqwik */
		if (gridSporkType == GridSpork.Type.UNDEFINED && signature.length > 52) {
			gridSporkType = GridSpork.Type.MINT_STORAGE;
		}

		final GridSpork gridSpork = GridSpork.create(gridSporkType);

		gridSpork.setTimeStamp(time);
		gridSpork.setPreviousTimeStamp(previousTime);
		gridSpork.setData(chunkData(gridSporkType));
		gridSpork.setPreviousData(chunkData(gridSporkType));
		gridSpork.setSignature(signature);

		return Arbitraries.of(gridSpork);
	}
}
