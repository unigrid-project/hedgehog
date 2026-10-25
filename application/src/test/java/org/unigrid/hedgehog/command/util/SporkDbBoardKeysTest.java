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

package org.unigrid.hedgehog.command.util;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.ExclusiveLock;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysRecord;
import org.unigrid.hedgehog.model.spork.BoardKeysRecordFixture;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
import picocli.CommandLine;

/* The spork-db commands go by the board keys of the database they are given. The release builds in keys 0 and 1; the
   first change adds key 2, the second switches key 0 off, and another first change adds key 3 instead of key 2. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SporkDbBoardKeysTest {
	private static final String BOARD = "--network-keys=" + key(0).getPublicKey() + "," + key(1).getPublicKey();
	private static final String NODE = "/node";
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange FORKING = change(ROOT, List.of(), keys(0, 1, 3), keys(0, 1, 3));

	@BeforeTry
	public void before() {
		new MockUp<BoardKeyOptions>() {
			@Mock String environmentKey() {
				return null;
			}
		};

		OptionsCli.parse(List.of("util"), BOARD);
		BoardKeysRecordFixture.inMemory();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static HedgehogCli.Result run(FileSystem fs, String... args) {
		final List<String> arguments = new ArrayList<>(List.of("util", "spork-db"));
		final ExclusiveLock.Locker free = (file, channel) -> channel.tryLock();

		arguments.addAll(Arrays.asList(args));
		arguments.add(BOARD);
		NetworkKey.reset();

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class, new CommandLine.IFactory() {
			@Override
			public <K> K create(Class<K> type) throws Exception {
				return type == SporkDbImport.class ? type.cast(new SporkDbImport(() -> fs.getPath(NODE), free))
					: CommandLine.defaultFactory().create(type);
			}
		}).registerConverter(Path.class, fs::getPath).setParameterExceptionHandler(BoardKeyOptions::reportWithoutKeys),
			arguments.toArray(String[]::new));
	}

	@SneakyThrows
	private static Path written(Path file, GridSpork... sporks) {
		final SporkDatabase database = SporkDatabase.builder().build();

		Arrays.stream(sporks).forEach(database::set);
		Files.createDirectories(file.getParent());
		SporkDatabase.persist(file, database);
		return file;
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	private static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.archive();
		spork.setTimeStamp(Instant.now().minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	private static BoardKeys ofTheNewBoard() {
		return signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2));
	}

	private static List<String> linesOf(HedgehogCli.Result result) {
		return result.out().lines().toList();
	}

	@Example
	@SneakyThrows
	public void validatesEverySporkByTheBoardKeysOfTheFile() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path counting = written(fs.getPath("/counting.db"), ofTheNewBoard(), supply(key(1), key(2)));
			final Path switchedOff = written(fs.getPath("/off.db"), ofTheNewBoard(), supply(key(0), key(1)));
			final HedgehogCli.Result valid = run(fs, "validate", counting.toString());
			final HedgehogCli.Result invalid = run(fs, "validate", switchedOff.toString());

			assertThat(valid.err(), valid.exitCode(), equalTo(0));
			assertThat(linesOf(valid), hasItem(startsWith("BOARD_KEYS: made")));
			assertThat(invalid.exitCode(), equalTo(1));
			assertThat(linesOf(invalid), hasItem(containsString("signed by board keys that are switched off")));
		}
	}

	/* Two board members sign in turn: key 2, which the chain of the file added, and then key 1 */
	@Example
	@SneakyThrows
	public void signsWithTheKeysThatTheChainOfTheFileMakesActive() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = written(fs.getPath("/off.db"), ofTheNewBoard(), supply(key(0), key(1)));
			final HedgehogCli.Result first = run(fs, "sign", file.toString(), "--output", "/once.db", "-k",
				key(2).getPrivateKey());
			final HedgehogCli.Result second = run(fs, "sign", "/once.db", "--output", "/twice.db", "-k",
				key(1).getPrivateKey());
			final HedgehogCli.Result off = run(fs, "sign", file.toString(), "--output", "/never.db", "-k",
				key(0).getPrivateKey());

			assertThat(first.err(), first.exitCode(), equalTo(0));
			assertThat(second.err(), second.exitCode(), equalTo(0));
			assertThat(run(fs, "validate", "/twice.db").exitCode(), equalTo(0));
			assertThat(off.exitCode(), equalTo(2));
			assertThat(off.err(), containsString("not one of the current board keys"));
			assertThat(Files.exists(fs.getPath("/never.db")), is(false));
		}
	}

	/* The node holds both changes, which switched key 0 off; keys 0 and 1 signed the first change alone as anyone who
	   holds them can */
	@Example
	@SneakyThrows
	public void importsNoDatabaseThatTakesTheBoardKeysOfTheNodeBackOrElsewhere() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path node = written(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE), ofTheNewBoard());
			final byte[] held = Files.readAllBytes(node);
			final Path shorter = written(fs.getPath("/shorter.db"), signed(chainOf(ADDING), key(0), key(1)));
			final Path forked = written(fs.getPath("/forked.db"), signed(chainOf(FORKING), key(0), key(3)));
			final Path none = written(fs.getPath("/none.db"), supply(key(1), key(2)));

			for (Path file : List.of(shorter, forked, none)) {
				final HedgehogCli.Result result = run(fs, "import", file.toString());

				assertThat(file.toString(), result.exitCode(), equalTo(1));
				assertThat(result.err(), containsString("chain of board keys of the node"));
				assertThat(Files.readAllBytes(node), equalTo(held));
			}

			assertThat(run(fs, "import", written(fs.getPath("/same.db"), ofTheNewBoard(), supply(key(1), key(2)))
				.toString()).exitCode(), equalTo(0));
		}
	}

	/* A file that is not imported moves the record of this machine nowhere, whatever its chain holds */
	@Example
	@SneakyThrows
	public void leavesTheRecordAsItWasWhenADatabaseIsNotImported() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path unsigned = written(fs.getPath("/unsigned.db"), ofTheNewBoard(), supply(key(0), key(3)));

			Files.createDirectories(fs.getPath(NODE));
			OptionsCli.parse(List.of("util"), BOARD);
			BoardKeysRecord.ofThisMachine().keep(Optional.of(List.of(ADDING)), "a test");

			assertThat(run(fs, "import", unsigned.toString()).exitCode(), equalTo(1));
			assertThat(BoardKeysRecord.ofThisMachine().read().orElseThrow(), hasSize(1));
		}
	}

	/* A node that holds no database yet holds no chain to judge a file by, while this machine verified both changes
	   before: a file that holds less of the chain, another chain or none is refused as the other commands refuse it */
	@Example
	@SneakyThrows
	public void importsNoDatabaseThatHoldsLessThanTheRecordOfThisMachineOrPartsFromIt() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path node = fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE);
			final KeyChange beyondTheFork = change(ROOT, List.of(FORKING), keys(1, 3), keys(1, 3));
			final Path shorter = written(fs.getPath("/shorter.db"), signed(chainOf(ADDING), key(0), key(1)));
			final Path forked = written(fs.getPath("/forked.db"), signed(chainOf(FORKING, beyondTheFork), key(1),
				key(3)));
			final Path none = written(fs.getPath("/none.db"), supply(key(1), key(2)));

			Files.createDirectories(node.getParent());
			OptionsCli.parse(List.of("util"), BOARD);
			BoardKeysRecord.ofThisMachine().keep(Optional.of(List.of(ADDING, SWITCHING_OFF)), "a test");

			final Map<Path, String> refusals = Map.of(shorter, "fewer than the 2 changes", forked,
				"parts at change 0", none, "holds no board keys");

			refusals.forEach((file, refusal) -> {
				final HedgehogCli.Result result = run(fs, "import", file.toString());

				assertThat(file.toString(), result.exitCode(), equalTo(2));
				assertThat(result.err(), containsString(refusal));
				assertThat(Files.exists(node), is(false));
			});

			assertThat(run(fs, "import", written(fs.getPath("/same.db"), ofTheNewBoard(), supply(key(1), key(2)))
				.toString()).exitCode(), equalTo(0));
		}
	}

	/* A database that was installed is imported, also when its chain cannot be recorded then: the record may not be
	   written, as here where a directory stands in the way of its lock, or another run may have recorded more of the
	   chain, or another chain, since the file was judged */
	@Example
	@SneakyThrows
	public void importsADatabaseThatWasInstalledWhenItsChainCannotBeRecordedThen() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path node = fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE);
			final Path file = written(fs.getPath("/new.db"), ofTheNewBoard(), supply(key(1), key(2)));

			Files.createDirectories(node.getParent());
			OptionsCli.parse(List.of("util"), BOARD);

			final Path record = BoardKeysRecord.ofThisMachine().getFile();

			Files.createDirectories(record.resolveSibling(record.getFileName() + ".lock"));

			final HedgehogCli.Result result = run(fs, "import", file.toString());

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(result.err(), containsString("was not recorded"));
			assertThat(Files.exists(node), is(true));
		}
	}
}
