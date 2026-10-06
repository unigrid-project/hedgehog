/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
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
	/* The value released builds computed for this class, so the spork.db files they wrote keep loading */
	private static final long serialVersionUID = 5650360876357285601L;

	public static final String SPORK_DB_FILE = "spork.db";

	private static final Map<Type, Function<SporkDatabase, GridSpork>> GETTERS = Map.of(
		Type.MINT_STORAGE, SporkDatabase::getMintStorage,
		Type.MINT_SUPPLY, SporkDatabase::getMintSupply,
		Type.VESTING_STORAGE, SporkDatabase::getVestingStorage,
		Type.STORAGE, SporkDatabase::getStorageSpork,
		Type.COMMUNICATION, SporkDatabase::getCommunicationSpork,
		Type.STATISTICS_PUBKEY, SporkDatabase::getStatisticsPubKey
	);

	private static final Map<Type, BiConsumer<SporkDatabase, GridSpork>> SETTERS = Map.of(
		Type.MINT_STORAGE, (db, spork) -> db.setMintStorage((MintStorage) spork),
		Type.MINT_SUPPLY, (db, spork) -> db.setMintSupply((MintSupply) spork),
		Type.VESTING_STORAGE, (db, spork) -> db.setVestingStorage((VestingStorage) spork),
		Type.STORAGE, (db, spork) -> db.setStorageSpork((StorageSpork) spork),
		Type.COMMUNICATION, (db, spork) -> db.setCommunicationSpork((CommunicationSpork) spork),
		Type.STATISTICS_PUBKEY, (db, spork) -> db.setStatisticsPubKey((StatisticsPubKey) spork)
	);

	private MintStorage mintStorage;
	private MintSupply mintSupply;
	private VestingStorage vestingStorage;
	private StorageSpork storageSpork;
	private CommunicationSpork communicationSpork;

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
		return accessor(GETTERS, gridSporkType, "Unsupported spork type requested from database").apply(this);
	}

	public void set(GridSpork gridSpork) {
		accessor(SETTERS, gridSpork.getType(), "Unsupported spork type sent to database").accept(this, gridSpork);
	}

	private static <A> A accessor(Map<Type, A> accessors, Type type, String unsupported) {
		return Optional.ofNullable(accessors.get(type)).orElseThrow(() -> new IllegalArgumentException(unsupported));
	}
}
