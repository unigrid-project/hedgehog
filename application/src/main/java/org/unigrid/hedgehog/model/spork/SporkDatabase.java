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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Cleanup;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

@Slf4j
@Data @Builder
@NoArgsConstructor
@AllArgsConstructor
public class SporkDatabase implements Serializable {
	public static final String SPORK_DB_FILE = "spork.db";

	private MintStorage mintStorage;
	private MintSupply mintSupply;
	private VestingStorage vestingStorage;

	private StatisticsPubKey statisticsPubKey;

	public static SporkDatabase load(Path path) throws IOException {
		@Cleanup final InputStream stream = Files.newInputStream(path, StandardOpenOption.READ);
		return SerializationUtils.deserialize(stream);
	}

	public static void persist(Path path, SporkDatabase sporkDatabase) throws IOException {
		@Cleanup final OutputStream stream = Files.newOutputStream(path,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE
		);

		SerializationUtils.serialize(sporkDatabase, stream);
	}

	public GridSpork get(Type gridSporkType) {
		switch (gridSporkType) {
			case MINT_STORAGE: return mintStorage;
			case MINT_SUPPLY: return mintSupply;
			case VESTING_STORAGE: return vestingStorage;
			case STATISTICS_PUBKEY: return statisticsPubKey;
			default: throw new IllegalArgumentException("Unsupported spork type requested from database");
		}
	}

	public void set(GridSpork gridSpork) {
		switch (gridSpork.getType()) {
			case MINT_STORAGE:
				mintStorage = (MintStorage) gridSpork;
				break;

			case MINT_SUPPLY:
				mintSupply = (MintSupply) gridSpork;
				break;

			case VESTING_STORAGE:
				vestingStorage = (VestingStorage) gridSpork;
				break;

			case STATISTICS_PUBKEY:
				statisticsPubKey = (StatisticsPubKey) gridSpork;

			default:
				throw new IllegalArgumentException("Unsupported spork type sent to database");
		}
	}
}
