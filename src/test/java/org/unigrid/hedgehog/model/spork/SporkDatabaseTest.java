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

import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.domains.Domain;
import static com.shazam.shazamcrest.matcher.Matchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.SuiteDomain;

public class SporkDatabaseTest extends BaseSporkDatabaseTest {
	@Inject
	private ApplicationDirectory applicationDirectory;

	@SneakyThrows
	@Property(tries = 200)
	@Domain(SuiteDomain.class)
	public void shouldRetainIntegrity(@ForAll("provideGridSpork") @NotNull GridSpork gridSpork) {
		Files.createDirectories(applicationDirectory.getUserDataDir());

		final Path path = Path.of(applicationDirectory.getUserDataDir().toString(), SporkDatabase.SPORK_DB_FILE);
		final SporkDatabase sporkDatabase = db(path);

		set(sporkDatabase, gridSpork);
		SporkDatabase.persist(path, sporkDatabase);
		final SporkDatabase deserializedSporkDatabase = SporkDatabase.load(path);

		assertThat(deserializedSporkDatabase, sameBeanAs(sporkDatabase));
		assertThat(deserializedSporkDatabase, equalTo(sporkDatabase));
	}
}
