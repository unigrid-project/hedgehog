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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import picocli.CommandLine;

/* A change of the board keys as the board makes it on its own machines: proposed, approved by each member, applied to
   a spork database, signed twice and imported. The release builds in keys 0 and 1; the change adds key 2 and switches
   key 0 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysCommandTest {
	private static final String BOARD = "--network-keys=" + key(0).getPublicKey() + "," + key(1).getPublicKey();

	@BeforeTry
	public void before() {
		new MockUp<BoardKeyOptions>() {
			@Mock String environmentKey() {
				return null;
			}
		};

		OptionsCli.parse(List.of("util"), BOARD);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static HedgehogCli.Result run(FileSystem fs, String... args) {
		final List<String> arguments = new ArrayList<>(List.of("util"));
		final ExclusiveLockFactory locks = new ExclusiveLockFactory(fs);

		arguments.addAll(Arrays.asList(args));
		arguments.add(BOARD);
		NetworkKey.reset();

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class, locks).registerConverter(Path.class,
			fs::getPath).setParameterExceptionHandler(BoardKeyOptions::reportWithoutKeys),
			arguments.toArray(String[]::new));
	}

	/* The import installs into the data directory of a node in memory, whose file system gives everyone a lock */
	private record ExclusiveLockFactory(FileSystem fs) implements CommandLine.IFactory {
		@Override
		public <K> K create(Class<K> type) throws Exception {
			return type == SporkDbImport.class ? type.cast(new SporkDbImport(() -> fs.getPath("/node"),
				(file, channel) -> channel.tryLock())) : CommandLine.defaultFactory().create(type);
		}
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	private static Path withSupply(FileSystem fs, Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();
		final SporkDatabase database = SporkDatabase.builder().build();
		final Path file = fs.getPath("/spork.db");

		spork.archive();
		spork.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		database.set(spork);
		SporkDatabase.persist(file, database);
		return file;
	}

	private static HedgehogCli.Result proposeTheChange(FileSystem fs, String... more) {
		final List<String> arguments = new ArrayList<>(List.of("board-keys", "propose", "--activate",
			key(2).getPublicKey(), "--deactivate", key(0).getPublicKey(), "--output", "/change.json"));

		arguments.addAll(Arrays.asList(more));
		return run(fs, arguments.toArray(String[]::new));
	}

	private static HedgehogCli.Result approve(FileSystem fs, Signature key) {
		return run(fs, "board-keys", "approve", "/change.json", "-k", key.getPrivateKey());
	}

	private static void assertSucceeded(HedgehogCli.Result result) {
		assertThat(result.err(), result.exitCode(), equalTo(0));
	}

	@Example
	@SneakyThrows
	public void makesAChangeThatTheBoardSignsAndANodeImports() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result proposed = proposeTheChange(fs, "--spork-db", withSupply(fs, key(0), key(1))
				.toString());

			assertSucceeded(proposed);
			assertThat(proposed.out(), containsString("quorum: 2 of the 2 keys active before it"));
			assertThat(proposed.out(), containsString("approve too: " + key(2).getPublicKey()));

			for (Signature approver : keys(0, 1, 2)) {
				assertSucceeded(approve(fs, approver));
			}

			assertSucceeded(run(fs, "board-keys", "apply", "/spork.db", "--change", "/change.json", "--output",
				"/changed.db"));
			assertSucceeded(run(fs, "spork-db", "sign", "/changed.db", "--output", "/once.db", "-k",
				key(1).getPrivateKey()));
			assertSucceeded(run(fs, "spork-db", "sign", "/once.db", "--output", "/twice.db", "-k",
				key(2).getPrivateKey()));
			Files.createDirectories(fs.getPath("/node"));
			assertSucceeded(run(fs, "spork-db", "import", "/twice.db"));

			final SporkDatabase imported = SporkDatabase.load(fs.getPath("/node", SporkDatabase.SPORK_DB_FILE));
			final BoardKeys boardKeys = imported.getBoardKeys();

			assertThat(boardKeys.chainKeys().orElseThrow().active(), equalTo(Set.of(publicKeysOf(keys(1, 2)))));
			assertThat(imported.getMintSupply().isTakenByTheNetwork(), is(true));
		}
	}

	@Example
	@SneakyThrows
	public void proposesNoChangeThatTheChainCannotTake() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final String first = key(0).getPublicKey();
			final String second = key(1).getPublicKey();

			assertThat(run(fs, "board-keys", "propose", "--activate", first, "--output", "/a.json").err(),
				containsString("already active"));
			assertThat(run(fs, "board-keys", "propose", "--deactivate", first, "--output", "/b.json").err(),
				containsString("2 to 16"));
			assertThat(run(fs, "board-keys", "propose", "--deactivate", key(3).getPublicKey(), "--output",
				"/c.json").err(), containsString("not active"));
			assertThat(run(fs, "board-keys", "propose", "--activate", second.substring(1), "--output", "/d.json")
				.err(), containsString("public key"));
			assertThat(run(fs, "board-keys", "propose", "--output", "/e.json").err(), containsString("changes nothing"));
			assertThat(Files.list(fs.getPath("/")).filter(file -> file.toString().endsWith(".json")).count(),
				is(0L));
		}
	}

	@Example
	@SneakyThrows
	public void takesAnApprovalOnlyOfAKeyThatIsActiveBeforeTheChangeOrThatItAdds() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			assertSucceeded(proposeTheChange(fs));

			final HedgehogCli.Result stranger = approve(fs, key(3));
			final HedgehogCli.Result approved = approve(fs, key(1));

			assertThat(stranger.exitCode(), equalTo(2));
			assertThat(stranger.err(), containsString("neither"));
			assertSucceeded(approved);
			assertThat(approved.out(), containsString("approvals: 1 of the 2 that the keys before it need"));
			assertThat(approved.out().lines().toList(), hasItem("added keys that approved it: 0 of 1"));
		}
	}

	@Example
	@SneakyThrows
	public void appliesNoChangeThatTooFewKeysApproved() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			withSupply(fs, key(0), key(1));
			assertSucceeded(proposeTheChange(fs));
			assertSucceeded(approve(fs, key(1)));
			assertSucceeded(approve(fs, key(2)));

			final HedgehogCli.Result applied = run(fs, "board-keys", "apply", "/spork.db", "--change",
				"/change.json", "--output", "/changed.db");

			assertThat(applied.exitCode(), equalTo(2));
			assertThat(applied.err(), containsString("1 of the 2 keys before it approved it"));
			assertThat(Files.exists(fs.getPath("/changed.db")), is(false));
		}
	}
}
