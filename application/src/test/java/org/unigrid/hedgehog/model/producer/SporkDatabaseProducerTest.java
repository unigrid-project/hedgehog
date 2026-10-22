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

package org.unigrid.hedgehog.model.producer;

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import lombok.Cleanup;
import lombok.Data;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.spork.BaseSporkDatabaseTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.util.Reflection;

public class SporkDatabaseProducerTest extends BaseSporkDatabaseTest {
	@Inject
	private ApplicationDirectory applicationDirectory;

	@Data
	public static class BrokenSporkDatabase implements Serializable {
		private int justSomeRandomProperty = 42;
	}

	private static <D extends Serializable> void persist(Path path, D database) throws IOException {
		@Cleanup final OutputStream stream = Files.newOutputStream(path,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE
		);

		SerializationUtils.serialize(database, stream);
	}

	@Example
	@SneakyThrows
	public void shouldReplaceIncompatibleDatabase() {
		Files.createDirectories(applicationDirectory.getUserDataDir());

		final Path path = Path.of(applicationDirectory.getUserDataDir().toString(), SporkDatabase.SPORK_DB_FILE);
		final BrokenSporkDatabase brokenSporkDatabase = new BrokenSporkDatabase();

		persist(path, brokenSporkDatabase);
		final byte[] original = Files.readAllBytes(path);

		final SporkDatabaseProducer producer = new SporkDatabaseProducer();
		FieldUtils.writeField(producer, "applicationDirectory", applicationDirectory, true);
		final SporkDatabase db = Reflection.invoke(producer, "produce");

		/* When a problem is detected during deserialization, the spork database producer
		   should create a fresh (correct) instance of the database, and keep the file it could not read */
		assertThat(db, equalTo(SporkDatabase.builder().build()));
		assertThat(Files.exists(path), is(false));

		try (Stream<Path> files = Files.list(path.getParent())) {
			final List<Path> aside = files.filter(file -> file.getFileName().toString()
				.startsWith(SporkDatabase.SPORK_DB_FILE + ".unreadable-")).toList();

			assertThat(aside, hasSize(1));
			assertThat(Files.readAllBytes(aside.get(0)), equalTo(original));
			Files.delete(aside.get(0));
		}
	}
}
