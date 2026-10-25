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

package org.unigrid.hedgehog.command.option;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeyOptionsTest {
	private static final Signature BOARD = keyPair();
	private static final Signature STRANGER = keyPair();
	private static final String KEY_FILE = "/keys/board.key";
	private static final String OWNER_ONLY = LedgerKeyFixtures.OWNER_ONLY;

	@Command(name = "signing")
	public static final class Signing implements Runnable {
		@Mixin private BoardKeyOptions boardKey;
		private String resolved;

		public Signing(FileSystem fileSystem) {
			boardKey = new BoardKeyOptions(fileSystem);
		}

		@Override
		public void run() {
			resolved = boardKey.resolve(NetworkKey::isTrusted);
		}
	}

	private record Outcome(int exitCode, String resolved, String err) { }

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	private static void environment(String value) {
		new MockUp<BoardKeyOptions>() {
			@Mock String environmentKey() {
				return value;
			}
		};
	}

	@BeforeTry
	public void fakeTheBoardAndAnEmptyEnvironment() {
		environment(null);

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { BOARD.getPublicKey() };
			}
		};
	}

	@SneakyThrows
	private static Outcome run(FileSystem fileSystem, String... arguments) {
		final Signing signing = new Signing(fileSystem);
		final StringWriter err = new StringWriter();
		final CommandLine command = new CommandLine(signing).setErr(new PrintWriter(err))
			.setParameterExceptionHandler(BoardKeyOptions::reportWithoutKeys);

		return new Outcome(command.execute(arguments), signing.resolved, err.toString());
	}

	@SneakyThrows
	private static Outcome run(String... arguments) {
		try (FileSystem fileSystem = LedgerKeyFixtures.posixFileSystem()) {
			return run(fileSystem, arguments);
		}
	}

	private static void assertRefused(Outcome outcome, String reason) {
		assertThat(outcome.err(), outcome.exitCode(), equalTo(CommandLine.ExitCode.USAGE));
		assertThat(outcome.err(), containsString(reason));
		assertThat(outcome.err(), not(containsString(BOARD.getPrivateKey())));
		assertThat(outcome.err(), not(containsString(STRANGER.getPrivateKey())));
		assertThat(outcome.err(), not(containsString("\tat ")));
		assertThat(outcome.resolved(), equalTo(null));
	}

	@Example
	public void shouldTakeTheKeyFromTheCommandLine() {
		assertThat(run("-k", BOARD.getPrivateKey()).resolved(), equalTo(BOARD.getPrivateKey()));
		assertThat(run("--key", BOARD.getPrivateKey()).resolved(), equalTo(BOARD.getPrivateKey()));
	}

	@Example
	public void shouldTakeTheKeyFromTheEnvironment() {
		environment(BOARD.getPrivateKey());
		assertThat(run().resolved(), equalTo(BOARD.getPrivateKey()));
	}

	@Example
	@SneakyThrows
	public void shouldTakeTheKeyFromAFileOnlyItsOwnerCanReadWithOrWithoutALineEnding() {
		for (String ending : List.of("", "\n", "\r\n")) {
			try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
				LedgerKeyFixtures.write(fs, KEY_FILE, BOARD.getPrivateKey() + ending, OWNER_ONLY);
				assertThat(run(fs, "--key-file", KEY_FILE).resolved(), equalTo(BOARD.getPrivateKey()));
			}
		}
	}

	private static String generatedKeysOf(Signature keys) {
		return "Private Key: " + keys.getPrivateKey() + "\nPublic Key: " + keys.getPublicKey() + "\n";
	}

	private static String valueOf(String printed, String label) {
		return printed.lines().filter(line -> line.startsWith(label)).findFirst().orElseThrow()
			.substring(label.length());
	}

	/* Board members keep their keys in the files that util key-generate wrote */
	@Example
	@SneakyThrows
	public void shouldTakeThePrivateKeyFromWhatKeyGenerateWrites() {
		final String generated = HedgehogCli.run("util", "key-generate").out();
		final String publicKey = valueOf(generated, "Public Key: ");
		final byte[] data = "a spork".getBytes(StandardCharsets.UTF_8);

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { publicKey };
			}
		};

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, generated, OWNER_ONLY);

			final String resolved = run(fs, "--key-file", KEY_FILE).resolved();
			final byte[] signed = new Signature(Optional.of(resolved), Optional.empty()).sign(data);

			assertThat(resolved, equalTo(valueOf(generated, "Private Key: ")));
			assertThat(new Signature(Optional.empty(), Optional.of(publicKey)).verify(data, signed), is(true));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileThatHoldsNoPrivateKeyOrMoreThanOne() {
		final String expected = "it takes the private board key in hex, or the lines that util key-generate writes";
		final String bothPrivate = "Private Key: " + BOARD.getPrivateKey() + "\nPrivate Key: "
			+ STRANGER.getPrivateKey() + "\n";

		for (List<String> refusal : List.of(List.of("Public Key: " + BOARD.getPublicKey() + "\n", "holds no private"),
			List.of(BOARD.getPublicKey() + "\n", "cannot be read as a board key"),
			List.of(bothPrivate, "holds more than one private key"),
			List.of("Private Key: not hex at all\n", "cannot be read as a board key"),
			List.of(generatedKeysOf(BOARD) + "Comment: kept by the treasurer\n", "cannot be read as a board key"))) {

			try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
				LedgerKeyFixtures.write(fs, KEY_FILE, refusal.getFirst(), OWNER_ONLY);

				final Outcome outcome = run(fs, "--key-file", KEY_FILE);

				assertRefused(outcome, KEY_FILE + " " + refusal.getLast());
				assertThat(outcome.err(), containsString(expected));
			}
		}
	}

	@Example
	public void shouldRefuseWhenNoSourceGivesAKey() {
		assertRefused(run(), "A board key is needed");

		environment("");
		assertRefused(run(), "A board key is needed");
	}

	@Example
	@SneakyThrows
	public void shouldRefuseWhenMoreThanOneSourceGivesAKey() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, BOARD.getPrivateKey(), OWNER_ONLY);
			assertRefused(run(fs, "-k", BOARD.getPrivateKey(), "--key-file", KEY_FILE), "-k and --key-file");

			environment(BOARD.getPrivateKey());
			assertRefused(run(fs, "-k", BOARD.getPrivateKey()), "-k and $" + BoardKeyOptions.ENVIRONMENT);
			assertRefused(run(fs, "--key-file", KEY_FILE), "--key-file and $" + BoardKeyOptions.ENVIRONMENT);
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileThatOthersCanRead() {
		for (String mode : List.of("rw-r-----", "rw----r--", "rw-r--r--", "rwxrwxrwx")) {
			for (String content : List.of(BOARD.getPrivateKey() + "\n", generatedKeysOf(BOARD))) {
				try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
					LedgerKeyFixtures.write(fs, KEY_FILE, content, mode);
					assertRefused(run(fs, "--key-file", KEY_FILE), "chmod 600");
				}
			}
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileThatCannotBeRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			Files.createDirectories(fs.getPath("/keys/directory"));
			LedgerKeyFixtures.write(fs, "/keys/large", BOARD.getPrivateKey().repeat(5), OWNER_ONLY);

			assertRefused(run(fs, "--key-file", "/keys/missing"), "/keys/missing");
			assertRefused(run(fs, "--key-file", "/keys/directory"), "regular file");
			assertRefused(run(fs, "--key-file", "/keys/large"), "more than a board key");
		}
	}

	/* A key given where a path or nothing belongs would end up on the terminal and in its scrollback if a message
	   repeated it */
	@Example
	public void shouldRepeatNoKeyGivenInTheWrongPlace() {
		final String key = STRANGER.getPrivateKey();

		for (List<String> arguments : List.of(List.of("--key-file", key), List.of("--key-file", key.toUpperCase()),
			List.of("-k", BOARD.getPrivateKey(), key), List.of(key), List.of("--kye", key))) {

			final Outcome outcome = run(arguments.toArray(String[]::new));

			assertRefused(outcome, "");
			assertThat(outcome.err(), not(containsString(key.toUpperCase())));
		}

		for (String[] arguments : List.of(new String[] { "cli", "gridspork-renew", key },
			new String[] { "cli", "gridspork-cosign", "--key-file", key, "00" })) {

			final HedgehogCli.Result cli = HedgehogCli.run(arguments);

			assertThat(cli.err(), cli.exitCode(), equalTo(CommandLine.ExitCode.USAGE));
			assertThat(cli.err(), not(containsString(key)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAKeyThatIsNoCurrentBoardKeyBeforeAnythingElse() {
		final String reason = "not one of the current board keys";

		assertRefused(run("-k", STRANGER.getPrivateKey()), reason);
		assertRefused(run("-k", "not hex at all"), reason);
		assertRefused(run("-k", ""), reason);

		for (String content : List.of(STRANGER.getPrivateKey(), generatedKeysOf(STRANGER))) {
			try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
				LedgerKeyFixtures.write(fs, KEY_FILE, content, OWNER_ONLY);
				assertRefused(run(fs, "--key-file", KEY_FILE), reason);
			}
		}
	}
}
