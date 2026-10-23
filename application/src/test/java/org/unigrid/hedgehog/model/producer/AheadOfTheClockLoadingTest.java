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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.reflect.FieldUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.util.Reflection;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;

/* A spork in the file of a node that is stamped far ahead of the clock would hold off every version the board makes
   until its time came, so the node leaves it out, as it leaves out what no board keys signed. The release builds in
   keys 0 and 1. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class AheadOfTheClockLoadingTest {
	private static final Instant IN_2100 = Instant.parse("2100-01-01T00:00:00Z");

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static <S extends GridSpork> S stamped(S spork, Instant at) {
		spork.setTimeStamp(at);
		spork.sign(key(0).getPrivateKey());
		spork.cosign(key(1).getPrivateKey());
		return spork;
	}

	private record Loaded(SporkDatabase database, List<String> warnings, long copiesAside) {
	}

	@SneakyThrows
	private static Loaded loaded(GridSpork... sporks) {
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(SporkDatabaseProducer.class);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path directory = Files.createDirectories(fs.getPath("/data"));
			final SporkDatabase stored = SporkDatabase.builder().build();
			final SporkDatabaseProducer producer = new SporkDatabaseProducer();

			Stream.of(sporks).forEach(stored::set);
			SporkDatabase.persist(directory.resolve(SporkDatabase.SPORK_DB_FILE), stored);
			FieldUtils.writeField(producer, "applicationDirectory", new ApplicationDirectory("test", "test") {
				@Override
				public Path getUserDataDir() {
					return directory;
				}
			}, true);

			log.start();
			logger.addAppender(log);

			final SporkDatabase database = Reflection.invoke(producer, "produce");

			try (Stream<Path> files = Files.list(directory)) {
				return new Loaded(database, log.list.stream().map(ILoggingEvent::getFormattedMessage).toList(),
					files.filter(file -> file.getFileName().toString().contains(".unsigned-")).count());
			}
		} finally {
			logger.detachAppender(log);
		}
	}

	private static MintSupply supply(long maxSupply, Instant at) {
		final MintSupply spork = (MintSupply) GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		return stamped(spork, at);
	}

	@Example
	public void shouldLeaveOutASporkStampedFarAheadAndSaySo() {
		final Loaded loaded = loaded(supply(10, IN_2100));

		assertThat(loaded.database().getMintSupply(), nullValue());
		assertThat(loaded.warnings(), hasItem(containsString("MINT_SUPPLY")));
		assertThat(loaded.warnings(), hasItem(containsString("ahead of the clock")));
		assertThat(loaded.copiesAside(), is(1L));
		assertThat(loaded(supply(10, Instant.now().minusSeconds(1))).database().getMintSupply(), notNullValue());
	}

	@Example
	public void shouldGoByNoBoardKeysStampedFarAhead() {
		final List<KeyChange> chain = List.of(change(rootOf(keys(0, 1)), List.of(), keys(0, 1, 2), keys(0, 1, 2)));
		final BoardKeys ahead = new BoardKeys();

		ahead.archive();
		ahead.setData(chainOf(chain.toArray(KeyChange[]::new)));

		final Loaded loaded = loaded(stamped(ahead, IN_2100));

		assertThat(loaded.database().getBoardKeys(), nullValue());
		assertThat(NetworkKey.getActiveKeys().length(), is(0));
		assertThat(loaded.warnings(), hasSize(1));
	}
}
