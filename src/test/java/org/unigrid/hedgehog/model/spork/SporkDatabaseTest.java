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

import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.domains.Domain;
import static org.hamcrest.MatcherAssert.assertThat;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.SuiteDomain;

public class SporkDatabaseTest extends BaseMockedWeldTest {
	private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

	@Inject
	private ApplicationDirectory applicationDirectory;

	@Inject
	private SporkDatabase managedSporkDatabase;

	@Provide
	public Arbitrary<GridSpork> provideGridSpork(@ForAll GridSpork.Type gridSporkType,
		@ForAll @ShortRange(min = 0, max = 3) short flags, @ForAll @Size(min = 50, max = 60) byte[] signature,
		@ForAll Instant time, @ForAll Instant previousTime) {

		return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
	}

	@SneakyThrows
	private SporkDatabase db(Path path) {
		if (Files.exists(path)) {
			return SporkDatabase.load(path);
		} else {
			return SporkDatabase.builder().build();
		}
	}

	@SneakyThrows
	@Property(tries = 200)
	@Domain(SuiteDomain.class)
	public void shouldRetainIntegrity(@ForAll("provideGridSpork") @NotNull GridSpork gridSpork) {
		Files.createDirectories(applicationDirectory.getUserDataDir());

		final Path path = Path.of(applicationDirectory.getUserDataDir().toString(), SporkDatabase.SPORK_DB_FILE);
		final SporkDatabase sporkDatabase = db(path);

		switch (gridSpork.getType()) {
			case MINT_STORAGE:
				sporkDatabase.setMintStorage((MintStorage) gridSpork);
				break;

			case MINT_SUPPLY:
				sporkDatabase.setMintSupply((MintSupply) gridSpork);
				break;

			case VESTING_STORAGE:
				sporkDatabase.setVestingStorage((VestingStorage) gridSpork);
		}

		SporkDatabase.persist(path, sporkDatabase);
		final SporkDatabase deserializedSporkDatabase = SporkDatabase.load(path);
		assertThat(deserializedSporkDatabase, sameBeanAs(sporkDatabase));
	}
}
