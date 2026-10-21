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
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class SporkDatabasePersistTest {
	private static final class RefusingSporkDatabase extends SporkDatabase {
		private static final long serialVersionUID = 4087090037341529003L;

		private void writeObject(ObjectOutputStream stream) throws IOException {
			throw new IOException("Refusing to write");
		}
	}

	private static SporkDatabase databaseWith(BigDecimal maxSupply) {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		final MintSupply mintSupply = new MintSupply();
		final MintSupply.SporkData data = mintSupply.getData();

		data.setMaxSupply(maxSupply);
		sporkDatabase.set(mintSupply);
		return sporkDatabase;
	}

	private static Path databaseIn(FileSystem fileSystem) throws IOException {
		final Path directory = fileSystem.getPath("/data");

		Files.createDirectories(directory);
		return directory.resolve(SporkDatabase.SPORK_DB_FILE);
	}

	private static void assertOnlyDatabaseIn(Path path) throws IOException {
		try (Stream<Path> entries = Files.list(path.getParent())) {
			assertThat(entries.count(), equalTo(1L));
		}
	}

	@Example
	public void shouldLoadWhatWasPersisted() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Path path = databaseIn(fileSystem);
			final SporkDatabase database = databaseWith(BigDecimal.TEN);

			SporkDatabase.persist(path, database);

			assertThat(SporkDatabase.load(path), sameBeanAs(database));
		}
	}

	@Example
	public void shouldReplaceAnExistingDatabase() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Path path = databaseIn(fileSystem);

			SporkDatabase.persist(path, databaseWith(BigDecimal.TEN));
			SporkDatabase.persist(path, databaseWith(BigDecimal.ONE));

			final MintSupply.SporkData data = SporkDatabase.load(path).getMintSupply().getData();
			assertThat(data.getMaxSupply(), equalTo(BigDecimal.ONE));
		}
	}

	@Example
	public void shouldLeaveNoTemporaryFileBehind() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Path path = databaseIn(fileSystem);

			SporkDatabase.persist(path, databaseWith(BigDecimal.TEN));

			assertOnlyDatabaseIn(path);
		}
	}

	@Example
	public void shouldKeepTheOldDatabaseWhenWritingFails() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Path path = databaseIn(fileSystem);
			final SporkDatabase database = databaseWith(BigDecimal.TEN);

			SporkDatabase.persist(path, database);
			assertThrows(IOException.class, () -> SporkDatabase.persist(path, new RefusingSporkDatabase()));

			assertThat(SporkDatabase.load(path), sameBeanAs(database));
			assertOnlyDatabaseIn(path);
		}
	}
}
