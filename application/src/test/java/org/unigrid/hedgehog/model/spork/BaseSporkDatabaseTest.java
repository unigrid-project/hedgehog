/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.SneakyThrows;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.ApplicationDirectoryMockUp;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.MINT_STORAGE;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.MINT_SUPPLY;
import static org.unigrid.hedgehog.model.spork.GridSpork.Type.VESTING_STORAGE;

public class BaseSporkDatabaseTest extends BaseMockedWeldTest {
	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	@BeforeContainer
	private static void beforeContainer() {
		new ApplicationDirectoryMockUp();
	}

	@Provide(ignoreExceptions = IllegalArgumentException.class)
	public Arbitrary<GridSpork> provideGridSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = 0, max = 3) short flags, @ForAll @Size(min = 50, max = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
	}

	@SneakyThrows
	protected SporkDatabase db(Path path) {
		if (Files.exists(path)) {
			return SporkDatabase.load(path);
		} else {
			return SporkDatabase.builder().build();
		}
	}

	protected static void set(SporkDatabase sporkDatabase, GridSpork gridSpork) {
		switch (gridSpork.getType()) {
			case MINT_STORAGE:
				sporkDatabase.setMintStorage((MintStorage) gridSpork);
				break;

			case MINT_SUPPLY:
				sporkDatabase.setMintSupply((MintSupply) gridSpork);
				break;

			case VESTING_STORAGE:
				sporkDatabase.setVestingStorage((VestingStorage) gridSpork);
				break;

			default:
				throw new IllegalArgumentException("Unsupported spork type passed.");
		}
	}
}
