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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.BoardKeysFixture;
import org.unigrid.hedgehog.model.spork.BoardKeysRecordFixture;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.ProposedKeyChange;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;
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
		BoardKeysRecordFixture.inMemory();
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
		return run(fs, "board-keys", "approve", "/change.json", "--spork-db", "/spork.db", "-k", key.getPrivateKey());
	}

	/* A change file as anyone can write it */
	@SneakyThrows
	private static void writeChange(FileSystem fs, ProposedKeyChange proposed) {
		Files.write(fs.getPath("/change.json"), new JsonConfiguration().getContext(ProposedKeyChange.class)
			.writeValueAsBytes(proposed));
	}

	private static KeyChange adding2AndSwitchingOff0(byte[] root) {
		return change(root, List.of(), keys(1, 2), List.of());
	}

	private static List<String> listOf(int... indices) {
		return new ArrayList<>(List.of(publicKeysOf(keys(indices))));
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

	/* Nothing signs the keys that a change file names as those before it, so a file that says the change only adds a
	   key, while it switches one off too, is refused before anything is signed */
	@Example
	@SneakyThrows
	public void approvesNoChangeWhoseFileHidesWhatItSwitchesOff() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			withSupply(fs, key(0), key(1));
			writeChange(fs, new ProposedKeyChange(listOf(1), adding2AndSwitchingOff0(rootOf(keys(0, 1)))));

			final byte[] tampered = Files.readAllBytes(fs.getPath("/change.json"));
			final HedgehogCli.Result result = approve(fs, key(1));

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString(ChangeFile.shortly(key(0).getPublicKey())));
			assertThat(Files.readAllBytes(fs.getPath("/change.json")), equalTo(tampered));
		}
	}

	@Example
	@SneakyThrows
	public void approvesNoChangeThatFollowsOnTheHeadOfAnotherChain() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			withSupply(fs, key(0), key(1));
			writeChange(fs, new ProposedKeyChange(listOf(0, 1), adding2AndSwitchingOff0(rootOf(keys(0, 3)))));

			final HedgehogCli.Result result = approve(fs, key(1));

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("does not follow on"));
		}
	}

	@Example
	@SneakyThrows
	public void readsNoChangeFileThatHoldsAnythingButKeys() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final List<String> escaped = listOf(0, 1);

			withSupply(fs, key(0), key(1));
			escaped.set(0, "\u001b[2J" + escaped.get(0).substring(4));
			writeChange(fs, new ProposedKeyChange(escaped, adding2AndSwitchingOff0(rootOf(keys(0, 1)))));

			final HedgehogCli.Result escape = approve(fs, key(1));
			final KeyChange malformed = adding2AndSwitchingOff0(rootOf(keys(0, 1)));

			malformed.getActive().set(1, malformed.getActive().get(1).substring(2) + "00");
			writeChange(fs, new ProposedKeyChange(listOf(0, 1), malformed));

			final HedgehogCli.Result wrongKey = approve(fs, key(1));

			for (HedgehogCli.Result result : List.of(escape, wrongKey)) {
				assertThat(result.exitCode(), equalTo(2));
				assertThat(result.err(), containsString("Cannot read the change of the board keys"));
				assertThat(result.err(), not(containsString("\u001b")));
			}
		}
	}

	@Example
	@SneakyThrows
	public void takesAnApprovalOnlyOfAKeyThatIsActiveBeforeTheChangeOrThatItAdds() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			withSupply(fs, key(0), key(1));
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

	/* A file of the chain of board keys, signed by key 1 and by the last key that the chain makes active */
	@SneakyThrows
	private static Path chained(FileSystem fs, String name, KeyChange... changes) {
		final SporkDatabase database = SporkDatabase.builder().build();
		final Path file = fs.getPath(name);
		final List<String> active = changes[changes.length - 1].getActive();
		final Signature cosigner = BoardKeysFixture.KEYS.stream()
			.filter(key -> key.getPublicKey().equals(active.getLast())).findFirst().orElseThrow();

		database.set(signed(chainOf(changes), key(1), cosigner));
		SporkDatabase.persist(file, database);
		return file;
	}

	private static HedgehogCli.Result sign(FileSystem fs, Path file, String output) {
		return run(fs, "spork-db", "sign", file.toString(), "--output", output, "-k", key(1).getPrivateKey());
	}

	private static void assertRefused(HedgehogCli.Result result, String why) {
		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), allOf(containsString(why), containsString("that this machine verified before"),
			containsString("only to start over")));
	}

	/* What this machine verified of the chain of board keys is recorded, and a file that holds less of it or another
	   chain is refused: what goes by it would take keys that the board switched off for active ones */
	@Example
	@SneakyThrows
	public void signsProposesApprovesAndAppliesNothingOverAFileThatHoldsLessThanTheRecordedChain() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final byte[] root = rootOf(keys(0, 1));
			final KeyChange first = change(root, List.of(), keys(1, 2), keys(0, 1, 2));
			final KeyChange other = change(root, List.of(), keys(0, 1, 3), keys(0, 1, 3));
			final HedgehogCli.Result recorded = sign(fs, chained(fs, "/chained.db", first), "/signed.db");

			assertSucceeded(recorded);
			assertThat(recorded.out(), containsString("Recorded the chain of 1 change"));

			final Path none = withSupply(fs, key(0), key(1));

			writeChange(fs, new ProposedKeyChange(listOf(0, 1), adding2AndSwitchingOff0(root)));
			assertRefused(sign(fs, none, "/none.db"), "holds no board keys");
			assertRefused(proposeTheChange(fs, "--spork-db", none.toString()), "holds no board keys");
			assertRefused(approve(fs, key(1)), "holds no board keys");
			assertRefused(run(fs, "board-keys", "apply", none.toString(), "--change", "/change.json", "--output",
				"/changed.db"), "holds no board keys");
			assertRefused(sign(fs, chained(fs, "/other.db", other), "/other-signed.db"), "does not hold");
			assertThat(Files.exists(fs.getPath("/none.db")), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void recordsTheLongerChainOfAFile() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final byte[] root = rootOf(keys(0, 1));
			final KeyChange first = change(root, List.of(), keys(1, 2), keys(0, 1, 2));
			final KeyChange second = change(root, List.of(first), keys(1, 2, 3), keys(1, 2, 3));

			assertSucceeded(sign(fs, chained(fs, "/first.db", first), "/first-signed.db"));
			assertSucceeded(sign(fs, chained(fs, "/second.db", first, second), "/second-signed.db"));
			assertRefused(sign(fs, chained(fs, "/again.db", first), "/again-signed.db"), "fewer than the chain of"
				+ " 2 changes");
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
