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

package org.unigrid.hedgehog.producer;

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
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.unigrid.hedgehog.model.spork.StatisticsPubKey;
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

	/* Seconds that an Instant holds, and that are found in the written file by their bytes, to be changed to seconds
	   that it does not */
	private static final long SECONDS_MARKED = 0x0001020304050607L;

	private static byte[] withSecondsBeyondAnInstant(byte[] file) {
		final byte[] marked = ByteBuffer.allocate(Long.BYTES).putLong(SECONDS_MARKED).array();
		final byte[] beyond = ByteBuffer.allocate(Long.BYTES).putLong(Long.MAX_VALUE).array();
		final byte[] changed = file.clone();

		for (int at = 0; at <= file.length - marked.length; at++) {
			if (Arrays.equals(file, at, at + marked.length, marked, 0, marked.length)) {
				System.arraycopy(beyond, 0, changed, at, beyond.length);
				return changed;
			}
		}

		throw new IllegalStateException("The time of the spork is not in the file");
	}

	/* The time of a spork is read by the Instant itself, which refuses seconds it cannot hold with a runtime exception
	   that the stream does not turn into an IOException. Such a file is moved aside like any other that cannot be read,
	   and the node starts: if the exception left the producer, the node would not exchange sporks until the file was
	   removed by hand, and a start would read the same file again. */
	@Example
	@SneakyThrows
	public void shouldMoveAsideAFileWhoseTimeIsBeyondTheRangeOfAnInstant() {
		final GridSpork spork = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, null, Optional.empty());
		final SporkDatabase stored = SporkDatabase.builder().build();

		fakeTheBoard(new Signature(), new Signature());
		spork.setTimeStamp(Instant.ofEpochSecond(SECONDS_MARKED));
		stored.set(spork);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path data = Files.createDirectories(fs.getPath("/data"));
			final Path file = data.resolve(SporkDatabase.SPORK_DB_FILE);

			SporkDatabase.persist(file, stored);
			Files.write(file, withSecondsBeyondAnInstant(Files.readAllBytes(file)));

			final byte[] original = Files.readAllBytes(file);
			final SporkDatabase db = produced(holding(data));
			final List<Path> aside = unreadableCopiesIn(data);

			assertThat(db, equalTo(SporkDatabase.builder().build()));
			assertThat(Files.exists(file), is(false));
			assertThat(aside, hasSize(1));
			assertThat(Files.readAllBytes(aside.getFirst()), equalTo(original));
		}
	}

	@SneakyThrows
	private static List<Path> unreadableCopiesIn(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().startsWith(SporkDatabase.SPORK_DB_FILE
				+ ".unreadable-")).toList();
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
	private static SporkDatabaseProducer producerOf(ApplicationDirectory directory) {
		final SporkDatabaseProducer producer = new SporkDatabaseProducer();

		FieldUtils.writeField(producer, "applicationDirectory", directory, true);
		return producer;
	}

	@SneakyThrows
	private static SporkDatabase produced(ApplicationDirectory directory) {
		return Reflection.invoke(producerOf(directory), "produce");
	}

	@SneakyThrows
	private static GridSpork signed(GridSpork.Type type, ChunkData data, Signature... signers) {
		final GridSpork spork = GridSpork.nextVersion(type, null, Optional.empty());

		spork.setData(data);
		spork.sign(signers[0].getPrivateKey());

		if (signers.length > 1) {
			spork.setCosignature(signers[1].signInOneForm(spork.getSignable()));
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

				final List<Path> copies = unsignedCopiesIn(fs.getPath("/data"));

				assertThat(db.getMintSupply().getSignature(), equalTo(taken.getSignature()));
				assertThat(db.get(GridSpork.Type.LEDGER_FEE), nullValue());
				assertThat(db.getMintStorage(), nullValue());
				assertThat(Files.readAllBytes(file), equalTo(original));
				assertThat(copies, hasSize(1));
				assertThat(Files.readAllBytes(copies.getFirst()), equalTo(original));
				assertThat(warningsOf(log), containsInAnyOrder(containsString("LEDGER_FEE"),
					containsString("MINT_STORAGE")));
				assertThat(warningsOf(log), everyItem(containsString("kept as " + copies.getFirst())));
			} finally {
				logger.detachAppender(log);
			}
		}
	}

	@SneakyThrows
	private static List<Path> unsignedCopiesIn(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.filter(file -> file.getFileName().toString().startsWith(SporkDatabase.SPORK_DB_FILE
				+ ".unsigned-")).toList();
		}
	}

	/* The next save writes only what the node keeps, and the board signs what it left out from the copy */
	@Example
	@SneakyThrows
	public void shouldKeepNoCopyWhenItKeepsEverySpork() {
		final Signature first = new Signature();
		final Signature second = new Signature();
		final MintSupply.SporkData supply = new MintSupply.SporkData();
		final SporkDatabase stored = SporkDatabase.builder().build();

		fakeTheBoard(first, second);
		supply.setMaxSupply(BigDecimal.TEN);
		stored.set(signed(GridSpork.Type.MINT_SUPPLY, supply, first, second));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);

			SporkDatabase.persist(file, stored);
			assertThat(produced(holding(fs.getPath("/data"))).getMintSupply(), notNullValue());
			assertThat(unsignedCopiesIn(fs.getPath("/data")), hasSize(0));
		}
	}

	/* What a file holds in the slot of a type is read as that type, so a spork the board signed as another one, or
	   with the data of another, would stand for what it is not */
	@Example
	@SneakyThrows
	public void shouldDropASporkThatSitsInTheSlotOfAnotherType() {
		final Signature first = new Signature();
		final Signature second = new Signature();
		final ListAppender<ILoggingEvent> log = new ListAppender<>();
		final Logger logger = (Logger) LoggerFactory.getLogger(SporkDatabaseProducer.class);

		fakeTheBoard(first, second);

		final MintStorage feeAsMints = new MintStorage();
		final StatisticsPubKey feeAsKey = new StatisticsPubKey();
		final SporkDatabase stored = SporkDatabase.builder().build();

		for (GridSpork spork : List.of(feeAsMints, feeAsKey)) {
			spork.setType(GridSpork.Type.LEDGER_FEE);
			spork.setData(new LedgerFee.SporkData());
			spork.archive();
			spork.sign(first.getPrivateKey());
			spork.cosign(second.getPrivateKey());
			assertThat(spork.isTakenByTheNetwork(), is(true));
		}

		stored.setMintStorage(feeAsMints);
		stored.setStatisticsPubKey(feeAsKey);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = Files.createDirectories(fs.getPath("/data")).resolve(SporkDatabase.SPORK_DB_FILE);

			SporkDatabase.persist(file, stored);
			log.start();
			logger.addAppender(log);

			try {
				assertThat(produced(holding(fs.getPath("/data"))), equalTo(SporkDatabase.builder().build()));
				assertThat(warningsOf(log), containsInAnyOrder(containsString("MINT_STORAGE"),
					containsString("STATISTICS_PUBKEY")));
				assertThat(warningsOf(log), everyItem(containsString("the slot of another type")));
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

	private static GridSpork supplyOf(long maxSupply) {
		final GridSpork spork = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, null, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		return spork;
	}

	@SneakyThrows
	private static Map<String, Object> identityOf(Path file) {
		return Files.readAttributes(file, "fileKey,lastModifiedTime");
	}

	@SneakyThrows
	private static List<String> namesIn(Path directory) {
		try (Stream<Path> files = Files.list(directory)) {
			return files.map(file -> file.getFileName().toString()).toList();
		}
	}

	@SneakyThrows
	private static BigDecimal maxSupplyIn(Path file) {
		return SporkDatabase.load(file).getMintSupply().<MintSupply.SporkData>getData().getMaxSupply();
	}

	/* The saver runs every few minutes whether or not a spork came in, and each run serializes the database, writes
	   it beside the file and forces it to the disk: a database that is what the last save wrote is left alone */
	@Example
	@SneakyThrows
	public void shouldWriteNothingWhenTheDatabaseIsWhatTheLastSaveWrote() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path data = Files.createDirectories(fs.getPath("/data"));
			final Path file = data.resolve(SporkDatabase.SPORK_DB_FILE);
			final SporkDatabaseProducer producer = producerOf(holding(data));
			final SporkDatabase db = Reflection.invoke(producer, "produce");

			db.set(supplyOf(10));
			Reflection.invoke(producer, "save");

			final Map<String, Object> written = identityOf(file);
			final byte[] original = Files.readAllBytes(file);

			Reflection.invoke(producer, "save");

			assertThat(identityOf(file), equalTo(written));
			assertThat(Files.readAllBytes(file), equalTo(original));
			assertThat(namesIn(data), contains(SporkDatabase.SPORK_DB_FILE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldWriteAgainOnceTheDatabaseChanged() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path data = Files.createDirectories(fs.getPath("/data"));
			final Path file = data.resolve(SporkDatabase.SPORK_DB_FILE);
			final SporkDatabaseProducer producer = producerOf(holding(data));
			final SporkDatabase db = Reflection.invoke(producer, "produce");

			db.set(supplyOf(10));
			Reflection.invoke(producer, "save");
			Reflection.invoke(producer, "save");
			assertThat(maxSupplyIn(file), equalTo(BigDecimal.valueOf(10)));

			db.set(supplyOf(20));
			Reflection.invoke(producer, "save");

			assertThat(maxSupplyIn(file), equalTo(BigDecimal.valueOf(20)));
			assertThat(namesIn(data), contains(SporkDatabase.SPORK_DB_FILE));
		}
	}

	/* What came in after the last timed save is still on the disk when the node stops */
	@Example
	@SneakyThrows
	public void shouldWriteWhatChangedSinceTheLastSaveWhenTheNodeStops() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path data = Files.createDirectories(fs.getPath("/data"));
			final Path file = data.resolve(SporkDatabase.SPORK_DB_FILE);
			final SporkDatabaseProducer producer = producerOf(holding(data));
			final SporkDatabase db = Reflection.invoke(producer, "produce");

			db.set(supplyOf(10));
			Reflection.invoke(producer, "save");
			Reflection.invoke(producer, "save");

			db.set(supplyOf(20));
			Reflection.invoke(producer, "destroy");

			assertThat(maxSupplyIn(file), equalTo(BigDecimal.valueOf(20)));
		}
	}

	/* A file that an operator removed while the node runs is written again, as it was before saves were skipped */
	@Example
	@SneakyThrows
	public void shouldWriteAgainWhenTheFileIsGone() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path data = Files.createDirectories(fs.getPath("/data"));
			final Path file = data.resolve(SporkDatabase.SPORK_DB_FILE);
			final SporkDatabaseProducer producer = producerOf(holding(data));
			final SporkDatabase db = Reflection.invoke(producer, "produce");

			db.set(supplyOf(10));
			Reflection.invoke(producer, "save");
			Files.delete(file);
			Reflection.invoke(producer, "save");

			assertThat(maxSupplyIn(file), equalTo(BigDecimal.valueOf(10)));
		}
	}
}
