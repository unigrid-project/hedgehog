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

package org.unigrid.hedgehog.service.ledger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.OWNER_ONLY;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import picocli.CommandLine;

@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerSetupTest {
	private static final int HASH_SIZE = 64;
	private static final String HASH = "9f".repeat(HASH_SIZE);
	private static final String GENESIS_FILE = "/genesis/ledger-genesis.json";
	private static final List<String> DAEMON = List.of("daemon");

	@Provide
	public Arbitrary<String> provideBadCheckpoint() {
		return Arbitraries.of(
			"12" + HASH,
			"12;" + HASH,
			"12:" + HASH.substring(1),
			"12:" + HASH + "0",
			"12:",
			"12",
			":" + HASH,
			"12:" + "g".repeat(HASH_SIZE * 2),
			"12:" + HASH.substring(0, HASH_SIZE * 2 - 1) + "z",
			"-5:" + HASH,
			"+5:" + HASH,
			"0:" + HASH,
			"1.5:" + HASH,
			"five:" + HASH,
			"99999999999999999999:" + HASH,
			"12:" + HASH + ":" + HASH,
			" 12:" + HASH,
			"12:" + HASH + " ",
			""
		);
	}

	private static void parse(FileSystem fs, String... options) {
		new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath).parseArgs(
			Stream.concat(DAEMON.stream(), Stream.of(options)).toArray(String[]::new)
		);
	}

	private static String messageOfValidating() {
		return assertThrows(IllegalArgumentException.class, LedgerSetup::validate).getMessage();
	}

	@Example
	public void shouldHaveNoTrustedCheckpointUnlessGivenOne() {
		OptionsCli.parse(DAEMON);

		assertThat(LedgerSetup.trusted(), equalTo(Optional.empty()));
	}

	@Property(tries = 30)
	public void shouldReadTheTrustedCheckpointItIsGiven(@ForAll @LongRange(min = 1) long height,
		@ForAll @Size(HASH_SIZE) byte[] hash, @ForAll boolean upperCase) {

		final String hex = HexFormat.of().formatHex(hash);

		OptionsCli.parse(DAEMON, "--ledger-trusted=" + height + ":" + (upperCase ? hex.toUpperCase() : hex));

		assertThat(LedgerSetup.trusted(), equalTo(Optional.of(new Checkpoint(height, hash))));
	}

	@Property
	public void shouldRefuseATrustedCheckpointItCannotRead(@ForAll("provideBadCheckpoint") String value) {
		OptionsCli.parse(DAEMON, "--ledger-trusted=" + value);

		assertThat(assertThrows(IllegalArgumentException.class, LedgerSetup::trusted).getMessage(),
			containsString("--ledger-trusted")
		);
	}

	@Example
	public void shouldNotLookAtWhatIsGivenWhileTheLedgerIsOff() {
		OptionsCli.parse(DAEMON, "--ledger-key=/nowhere.key", "--ledger-genesis=/nowhere.json",
			"--ledger-trusted=garbage"
		);

		LedgerSetup.validate();
	}

	@Example
	public void shouldAcceptALedgerWithOnlyTheDefaults() {
		OptionsCli.parse(DAEMON, "--ledger");

		LedgerSetup.validate();
	}

	@Example
	@SneakyThrows
	public void shouldAcceptAKeyFileAGenesisAndACheckpointThatAllHold() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.writeSeed(fs, new byte[32]);
			LedgerKeyFixtures.write(fs, GENESIS_FILE, "{}", null);
			parse(fs, "--ledger", "--ledger-key", KEY_FILE, "--ledger-genesis", GENESIS_FILE,
				"--ledger-trusted=12:" + HASH
			);

			LedgerSetup.validate();
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAKeyFileItCannotRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			parse(fs, "--ledger", "--ledger-key", KEY_FILE);

			assertThat(messageOfValidating(), containsString(KEY_FILE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAKeyFileOthersCanRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, "00".repeat(32) + "\n", "rw-r-----");
			parse(fs, "--ledger", "--ledger-key", KEY_FILE);

			assertThat(messageOfValidating(), containsString("chmod 600"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAKeyFileThatIsNotASeedWithoutRevealingIt() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, KEY_FILE, "ab".repeat(31) + "zz\n", OWNER_ONLY);
			parse(fs, "--ledger", "--ledger-key", KEY_FILE);

			final String message = messageOfValidating();

			assertThat(message, containsString(KEY_FILE));
			assertThat(message, not(containsString("abababab")));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAGenesisFileThatIsNotThere() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			parse(fs, "--ledger", "--ledger-genesis", GENESIS_FILE);

			assertThat(messageOfValidating(), containsString(GENESIS_FILE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAGenesisThatIsADirectory() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			Files.createDirectories(fs.getPath(GENESIS_FILE));
			parse(fs, "--ledger", "--ledger-genesis", GENESIS_FILE);

			assertThat(messageOfValidating(), containsString(GENESIS_FILE));
		}
	}

	@Example
	public void shouldRefuseABadTrustedCheckpoint() {
		OptionsCli.parse(DAEMON, "--ledger", "--ledger-trusted=12:abc");

		assertThat(messageOfValidating(), containsString("--ledger-trusted"));
	}
}
