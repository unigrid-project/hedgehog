/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.spork;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

/* A spork database is read as the classes it holds and nothing else, as a crafted file could otherwise run code on the
   machine that reads it through the readObject of some class it names */
public class SporkDatabaseFilterTest {
	private static final AtomicBoolean MADE = new AtomicBoolean();

	/* Says so when it is made from a stream, which a class that runs code would do as well */
	private static final class Tripwire implements Serializable, Comparable<Tripwire> {
		private static final long serialVersionUID = 1L;

		private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException {
			MADE.set(true);
			stream.defaultReadObject();
		}

		@Override
		public int compareTo(Tripwire other) {
			return 0;
		}
	}

	@SneakyThrows
	private static Path written(FileSystem fs, Object object) {
		final Path file = fs.getPath("/spork.db");

		try (OutputStream out = Files.newOutputStream(file); ObjectOutputStream stream = new ObjectOutputStream(out)) {
			stream.writeObject(object);
		}

		return file;
	}

	@SneakyThrows
	private static SporkDatabase fixture(String name) {
		return SporkDatabase.load(Path.of(SporkDatabaseFilterTest.class.getResource(name).toURI()));
	}

	/* Every type, with data, a previous version, a log and a co-signature, as a node of this release holds them; the
	   blacklist in the set its decoder makes */
	private static SporkDatabase everyType() {
		final SporkDatabase database = SporkDatabase.builder().build();
		final GridSporkProvider provider = new GridSporkProvider();

		for (GridSpork.Type type : GridSpork.Type.values()) {
			if (type != GridSpork.Type.UNDEFINED) {
				database.set(provider.provide(type, (short) 0, new byte[60], Instant.now(), Instant.EPOCH).sample());
			}
		}

		database.getLedgerBlacklist().<LedgerBlacklist.SporkData>getData().setLegacyAddresses(new TreeSet<>(Set.of(
			"00".repeat(LedgerBlacklist.SporkData.HASH_BYTES))));

		return database;
	}

	@Example
	public void shouldLoadTheDatabasesOfEarlierBuilds() {
		assertThat(fixture("/spork/spork-0.0.7.db").getVestingStorage(), notNullValue());
		assertThat(fixture("/spork/legacy-spork.db").getMintStorage(), notNullValue());
	}

	@Example
	@SneakyThrows
	public void shouldLoadADatabaseOfEveryTypeOfThisRelease() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final SporkDatabase database = everyType();
			final Path file = fs.getPath("/spork.db");

			SporkDatabase.persist(file, database);
			assertThat(SporkDatabase.load(file), sameBeanAs(database));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAClassThatNoSporkDatabaseHoldsWithoutMakingIt() {
		final PriorityQueue<Tripwire> queue = new PriorityQueue<>(List.of(new Tripwire()));
		final SporkDatabase database = everyType();
		final Set<Object> addresses = new HashSet<>(List.of(new Tripwire()));

		database.getLedgerBlacklist().<LedgerBlacklist.SporkData>getData().setLegacyAddresses((Set) addresses);
		MADE.set(false);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			assertThat(assertThrows(InvalidClassException.class, () -> SporkDatabase.load(written(fs, queue)))
				.getMessage(), containsString("java.util.PriorityQueue is no class of a spork database"));
			assertThat(assertThrows(InvalidClassException.class, () -> SporkDatabase.load(written(fs, database)))
				.getMessage(), containsString(Tripwire.class.getName() + " is no class of a spork database"));
			assertThat(MADE.get(), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseADatabaseNestedDeeperThanOneIs() {
		final SporkDatabase database = everyType();
		final List<Object> nested = new ArrayList<>();
		List<Object> innermost = nested;

		for (int i = 0; i < SporkDatabaseFilter.MAX_DEPTH; i++) {
			final List<Object> next = new ArrayList<>();

			innermost.add(next);
			innermost = next;
		}

		database.getLedgerBlacklist().<LedgerBlacklist.SporkData>getData().setLegacyAddresses(
			(Set) new HashSet<>(List.of(nested)));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			assertThat(assertThrows(InvalidClassException.class, () -> SporkDatabase.load(written(fs, database)))
				.getMessage(), containsString("larger or deeper than a spork database is"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAnArrayLongerThanOneOfASporkDatabase() {
		final SporkDatabase database = everyType();

		database.getMintSupply().setSignature(new byte[(int) SporkDatabaseFilter.MAX_ARRAY_LENGTH + 1]);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			assertThat(assertThrows(InvalidClassException.class, () -> SporkDatabase.load(written(fs, database)))
				.getMessage(), containsString("larger or deeper than a spork database is"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAnObjectThatIsNoSporkDatabase() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			assertThat(assertThrows(InvalidClassException.class, () -> SporkDatabase.load(written(fs,
				BigDecimal.ONE))).getMessage(), containsString("holds no spork database"));
		}
	}

	private record Info(Class<?> serialClass, long arrayLength, long depth, long references, long streamBytes)
		implements ObjectInputFilter.FilterInfo {
	}

	/* What a stream of the most a spork database can be reaches stays far within the limits; the limits themselves
	   are checked whatever the class, and before it is */
	@Example
	public void shouldRefuseAStreamBeyondTheLimits() {
		final List<Info> beyond = List.of(new Info(null, -1, SporkDatabaseFilter.MAX_DEPTH + 1, 1, 1),
			new Info(null, -1, 1, SporkDatabaseFilter.MAX_REFERENCES + 1, 1),
			new Info(null, -1, 1, 1, SporkDatabaseFilter.MAX_BYTES + 1),
			new Info(byte[].class, SporkDatabaseFilter.MAX_ARRAY_LENGTH + 1, 1, 1, 1),
			new Info(SporkDatabase.class, -1, SporkDatabaseFilter.MAX_DEPTH + 1, 1, 1));

		for (Info info : beyond) {
			assertThat(info.toString(), new SporkDatabaseFilter().checkInput(info),
				equalTo(ObjectInputFilter.Status.REJECTED));
		}

		assertThat(new SporkDatabaseFilter().checkInput(new Info(SporkDatabase.class, -1, 8, 225_311, 10_496_269)),
			equalTo(ObjectInputFilter.Status.ALLOWED));
	}
}
