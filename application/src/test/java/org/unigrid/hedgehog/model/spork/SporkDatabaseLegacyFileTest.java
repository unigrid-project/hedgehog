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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import lombok.Cleanup;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

/* The fixture was written by a build from before the storage spork, in the format every release stores */
public class SporkDatabaseLegacyFileTest {
	private static final String FIXTURE = "legacy-spork.db";

	@SneakyThrows
	private static Path inMemoryCopyOfFixture() {
		final Path path = Jimfs.newFileSystem(Configuration.unix()).getPath("/" + SporkDatabase.SPORK_DB_FILE);
		@Cleanup final InputStream fixture = SporkDatabaseLegacyFileTest.class.getResourceAsStream(FIXTURE);

		Files.copy(fixture, path);
		return path;
	}

	@SneakyThrows
	@Example
	public void loadsTheSporksOfEarlierBuilds() {
		final SporkDatabase database = SporkDatabase.load(inMemoryCopyOfFixture());
		final MintSupply.SporkData supply = database.getMintSupply().getData();
		final MintStorage.SporkData mints = database.getMintStorage().getData();
		final VestingStorage.SporkData vests = database.getVestingStorage().getData();

		assertThat(supply.getMaxSupply(), equalTo(new BigDecimal("150000000")));
		assertThat(database.getMintStorage().getTimeStamp(), equalTo(Instant.parse("2024-09-02T11:00:00Z")));
		assertThat(mints.getMints(), allOf(aMapWithSize(2),
			hasEntry(new Location(new Address("mint-address-1"), 1000), new BigDecimal("220000")),
			hasEntry(new Location(new Address("mint-address-2"), 2000), new BigDecimal("14115.5"))
		));

		assertThat(vests.getVestingAddresses(), allOf(aMapWithSize(1), hasEntry(new Address("vesting-address-1"),
			Vesting.builder().amount(new BigDecimal("5000")).block(3000).cliff(10).duration(Duration.ofDays(30))
				.parts(12).percent(5).start(Instant.parse("2024-10-01T00:00:00Z")).build())
		));

		assertThat(database.getStorageSpork(), nullValue());
		assertThat(database.getCommunicationSpork(), nullValue());
	}

	@SneakyThrows
	@Example
	public void keepsACommunicationSporkAddedToAnEarlierDatabase() {
		final Path path = inMemoryCopyOfFixture();
		final SporkDatabase database = SporkDatabase.load(path);

		database.set(new CommunicationSpork());
		SporkDatabase.persist(path, database);

		final SporkDatabase reloaded = SporkDatabase.load(path);

		assertThat(reloaded.getCommunicationSpork(), notNullValue());
		assertThat(reloaded, equalTo(database));
	}
}
