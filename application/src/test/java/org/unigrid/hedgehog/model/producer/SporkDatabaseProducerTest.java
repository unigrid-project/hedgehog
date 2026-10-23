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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Cleanup;
import lombok.Data;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BaseSporkDatabaseTest;
import org.unigrid.hedgehog.model.spork.ChunkData;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintSupply;
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

	@SneakyThrows
	private static void fakeTheBoard(Signature... keys) {
		final String[] publicKeys = Stream.of(keys).map(Signature::getPublicKey).toArray(String[]::new);

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return publicKeys;
			}
		};
	}

	/* A data directory in memory, which the producer reads its database from */
	private static ApplicationDirectory holding(Path dataDirectory) {
		return new ApplicationDirectory("test", "test") {
			@Override
			public Path getUserDataDir() {
				return dataDirectory;
			}
		};
	}

	@SneakyThrows
	private static SporkDatabase produced(ApplicationDirectory directory) {
		final SporkDatabaseProducer producer = new SporkDatabaseProducer();

		FieldUtils.writeField(producer, "applicationDirectory", directory, true);
		return Reflection.invoke(producer, "produce");
	}

	@SneakyThrows
	private static GridSpork signed(GridSpork.Type type, ChunkData data, Signature... signers) {
		final GridSpork spork = GridSpork.nextVersion(type, null, Optional.empty());

		spork.setData(data);
		spork.sign(signers[0].getPrivateKey());

		if (signers.length > 1) {
			spork.setCosignature(signers[1].sign(spork.getSignable()));
		}

		return spork;
	}

	private static List<String> warningsOf(ListAppender<ILoggingEvent> log) {
		return log.list.stream().filter(event -> event.getLevel() == Level.WARN)
			.map(ILoggingEvent::getFormattedMessage).toList();
	}

	/* A node hands out what it holds as what the network took, so of its own file it keeps only what the current board
	   signed: a tampered or stale file injects nothing. The file stays as it is until the node saves. */
	@Example
	@SneakyThrows
	public void shouldDropEverySporkThatTwoCurrentNetworkKeysDidNotSign() {
		final Signature first = new Signature();
		final Signature second = new Signature();
		final Signature stranger = new Signature();
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(SporkDatabaseProducer.class);

		fakeTheBoard(first, second);

		final MintSupply.SporkData supply = new MintSupply.SporkData();

		supply.setMaxSupply(BigDecimal.TEN);

		final GridSpork taken = signed(GridSpork.Type.MINT_SUPPLY, supply, first, second);
		final SporkDatabase stored = SporkDatabase.builder().build();

		stored.set(taken);
		stored.set(signed(GridSpork.Type.LEDGER_FEE, new LedgerFee.SporkData(), first));
		stored.set(signed(GridSpork.Type.MINT_STORAGE, new MintStorage().getData(), stranger, first));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);

			SporkDatabase.persist(file, stored);

			final byte[] original = Files.readAllBytes(file);

			log.start();
			logger.addAppender(log);

			try {
				final SporkDatabase db = produced(holding(fs.getPath("/data")));

				assertThat(db.getMintSupply().getSignature(), equalTo(taken.getSignature()));
				assertThat(db.get(GridSpork.Type.LEDGER_FEE), nullValue());
				assertThat(db.getMintStorage(), nullValue());
				assertThat(Files.readAllBytes(file), equalTo(original));
				assertThat(warningsOf(log), containsInAnyOrder(containsString("LEDGER_FEE"),
					containsString("MINT_STORAGE")));
			} finally {
				logger.detachAppender(log);
			}
		}
	}

	/* A spork that cannot even be encoded is one the node does not take, and the node starts all the same */
	@Example
	@SneakyThrows
	public void shouldDropASporkThatCannotBeEncodedAndStart() {
		final Signature first = new Signature();
		final Signature second = new Signature();

		fakeTheBoard(first, second);

		final GridSpork undefined = signed(GridSpork.Type.MINT_STORAGE, new MintStorage().getData(), first, second);
		final SporkDatabase stored = SporkDatabase.builder().build();

		stored.set(undefined);
		undefined.setType((short) 0);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);

			SporkDatabase.persist(file, stored);
			assertThat(produced(holding(fs.getPath("/data"))), equalTo(SporkDatabase.builder().build()));
		}
	}

	/* Written out, the amount is a billion digits: the node judges it before it encodes anything, also for a warning */
	@Example
	@SneakyThrows
	public void shouldDropASporkThatHoldsAnAmountBeyondTheBoundsOfTheWireWithoutWritingItOut() {
		final GridSpork beyond = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, null, Optional.empty());
		final MintSupply.SporkData previous = new MintSupply.SporkData();
		final SporkDatabase stored = SporkDatabase.builder().build();

		fakeTheBoard(new Signature(), new Signature());
		previous.setMaxSupply(new BigDecimal("1E+999999999"));
		beyond.setPreviousData(previous);
		stored.set(beyond);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);

			SporkDatabase.persist(file, stored);
			assertThat(produced(holding(fs.getPath("/data"))), equalTo(SporkDatabase.builder().build()));
		}
	}

	/* One key that the board no longer holds signed them, so a node that upgrades from 0.0.7 holds none of them and
	   takes the versions that the current board signed from its peers */
	@Example
	@SneakyThrows
	public void shouldHoldNothingOfTheSporksOfRelease007() {
		fakeTheBoard(new Signature(), new Signature());

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
			InputStream fixture = getClass().getResourceAsStream("/spork/spork-0.0.7.db")) {

			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);

			Files.write(file, fixture.readAllBytes());

			final SporkDatabase db = produced(holding(fs.getPath("/data")));

			assertThat(SporkDatabase.load(file).getMintStorage(), notNullValue());
			assertThat(db, equalTo(SporkDatabase.builder().build()));
		}
	}
}
