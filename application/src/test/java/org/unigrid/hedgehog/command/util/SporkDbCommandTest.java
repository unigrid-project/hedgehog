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

package org.unigrid.hedgehog.command.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.cli.SporkCommands;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import picocli.CommandLine;

/* The commands that check a spork database, sign it with board keys and install it as the database of a node. The
   files stay in memory and the board is two keys made for the run, which the network keys name. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
@AddLifecycleHook(value = RestoreOptionsHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SporkDbCommandTest {
	private static final Signature FIRST = keyPair();
	private static final Signature SECOND = keyPair();
	private static final String BOARD = "--network-keys=" + FIRST.getPublicKey() + "," + SECOND.getPublicKey();
	private static final String RELEASE_007 = "spork-0.0.7.db";
	private static final String LEGACY = "legacy-spork.db";
	private static final int SHOWN_HEX = 16;
	private static final Pattern LOGBACK_STATUS = Pattern.compile("\\d{2}:\\d{2}:\\d{2},\\d{3} \\|-");

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	@BeforeTry
	public void fakeTheBoard() {
		SporkCommands.environmentKey(null);
		OptionsCli.parse(List.of("util"), BOARD);
	}

	private static HedgehogCli.Result run(FileSystem fs, String... args) {
		final List<String> arguments = new ArrayList<>(List.of("util", "spork-db"));

		arguments.addAll(Arrays.asList(args));
		arguments.add(BOARD);

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath)
			.setParameterExceptionHandler(BoardKeyOptions::reportWithoutKeys), arguments.toArray(String[]::new));
	}

	@SneakyThrows
	private static Path fixture(FileSystem fs, String name) {
		final Path file = fs.getPath("/" + name);

		try (InputStream in = SporkDbCommandTest.class.getResourceAsStream("/spork/" + name)) {
			Files.write(file, in.readAllBytes());
		}

		return file;
	}

	@SneakyThrows
	private static Path written(FileSystem fs, String name, GridSpork... sporks) {
		final SporkDatabase database = SporkDatabase.builder().build();
		final Path file = fs.getPath("/" + name);

		Arrays.stream(sporks).forEach(database::set);
		SporkDatabase.persist(file, database);
		return file;
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	/* A first version, signed by the keys given */
	@SneakyThrows
	private static GridSpork supply(MintSupply.SporkData data, Signature... signers) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.setData(data);
		spork.sign(signers[0].getPrivateKey());

		if (signers.length > 1) {
			spork.cosign(signers[1].getPrivateKey());
		}

		return spork;
	}

	private static String shortly(String hex) {
		return StringUtils.left(hex, SHOWN_HEX);
	}

	/* Logback reports on the output, once in a run, a logger that logs with no appender to log to */
	private static List<String> linesOf(HedgehogCli.Result result) {
		return result.out().lines().filter(line -> !LOGBACK_STATUS.matcher(line).lookingAt()).toList();
	}

	private static String lineOf(HedgehogCli.Result result, Type type) {
		return linesOf(result).stream().filter(line -> line.startsWith(type + ":")).findFirst().orElseThrow();
	}

	@SneakyThrows
	private static SporkDatabase load(Path file) {
		return SporkDatabase.load(file);
	}

	/* As a build from before co-signatures stored it: signed once, by a key that is no board key */
	private static String unsigned(GridSpork spork) {
		return spork.getType() + ": made " + spork.getTimeStamp() + ", digest " + shortly(PendingSporks.digestOf(spork))
			+ ", signer not a board key, co-signer none, log entries 0, not signed by the board, content valid";
	}

	@Example
	@SneakyThrows
	public void reportsTheSporksOfAnEarlierBuildAsNotSignedByTheBoard() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = fixture(fs, LEGACY);
			final SporkDatabase database = load(file);
			final HedgehogCli.Result result = run(fs, "validate", file.toString());

			assertThat(result.err(), result.exitCode(), equalTo(1));
			assertThat(linesOf(result), equalTo(List.of(unsigned(database.getMintStorage()),
				unsigned(database.getMintSupply()), "VESTING_STORAGE: absent", "STORAGE: absent",
				"LEDGER_BLACKLIST: absent", "LEDGER_FEE: absent", "STATISTICS_PUBKEY: absent", "valid: no")));
		}
	}

	/* The mints of the fixture are made up, and are told apart from those the ledger can pay */
	@Example
	@SneakyThrows
	public void reportsTheSporksOfRelease007AsNotSignedByTheBoard() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = fixture(fs, RELEASE_007);
			final GridSpork mints = load(file).getMintStorage();
			final HedgehogCli.Result result = run(fs, "validate", file.toString());

			assertThat(result.err(), result.exitCode(), equalTo(1));
			assertThat(lineOf(result, Type.MINT_STORAGE), startsWith("MINT_STORAGE: made " + mints.getTimeStamp()
				+ ", digest " + shortly(PendingSporks.digestOf(mints)) + ", signer not a board key, co-signer none"
				+ ", log entries 0, not signed by the board, content not valid: The mint of "));
			assertThat(lineOf(result, Type.MINT_SUPPLY), containsString("not signed by the board, content valid"));
			assertThat(lineOf(result, Type.LEDGER_FEE), equalTo("LEDGER_FEE: absent"));

			/* A line for each type but UNDEFINED, and the verdict */
			assertThat(linesOf(result), hasSize(GridSpork.Type.values().length));
			assertThat(linesOf(result), hasItem("valid: no"));
		}
	}

	@Example
	@SneakyThrows
	public void passesADatabaseThatTheBoardSigned() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final GridSpork spork = supply(supplyOf(10), FIRST, SECOND);
			final HedgehogCli.Result result = run(fs, "validate", written(fs, "signed.db", spork).toString());

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(lineOf(result, Type.MINT_SUPPLY), equalTo("MINT_SUPPLY: made " + spork.getTimeStamp()
				+ ", digest " + shortly(PendingSporks.digestOf(spork)) + ", signer " + shortly(FIRST.getPublicKey())
				+ ", co-signer " + shortly(SECOND.getPublicKey()) + ", log entries 0, signed by the board, content"
				+ " valid"));
			assertThat(linesOf(result), hasItem("valid: yes"));
		}
	}

	@Example
	@SneakyThrows
	public void failsASporkSignedOnceOrWithContentThatDoesNotHold() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result once = run(fs, "validate", written(fs, "once.db",
				supply(supplyOf(10), FIRST)).toString());
			final HedgehogCli.Result negative = run(fs, "validate", written(fs, "negative.db",
				supply(supplyOf(-1), FIRST, SECOND)).toString());

			assertThat(once.exitCode(), equalTo(1));
			assertThat(lineOf(once, Type.MINT_SUPPLY), containsString("co-signer none, log entries 0, not signed by"
				+ " the board"));
			assertThat(negative.exitCode(), equalTo(1));
			assertThat(lineOf(negative, Type.MINT_SUPPLY), containsString("signed by the board, content not valid: A"
				+ " maximum supply is not negative"));
			assertThat(linesOf(negative), hasItem("valid: no"));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAFileThatHoldsNoSporkDatabase() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			Files.writeString(fs.getPath("/garbage.db"), "no spork database");

			final HedgehogCli.Result garbage = run(fs, "validate", "/garbage.db");
			final HedgehogCli.Result missing = run(fs, "validate", "/missing.db");

			assertThat(garbage.exitCode(), equalTo(2));
			assertThat(garbage.err(), containsString("Cannot read the spork database /garbage.db"));
			assertThat(garbage.out(), emptyString());
			garbage.assertNoStackTrace();
			assertThat(missing.exitCode(), equalTo(2));
			assertThat(missing.err(), containsString("Cannot read the spork database /missing.db"));
		}
	}
}
