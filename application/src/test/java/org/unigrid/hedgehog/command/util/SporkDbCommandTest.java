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
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.StringUtils;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.BoardKeyOptions;
import org.unigrid.hedgehog.command.option.OptionsCli;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.ledger.ExclusiveLock;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LedgerBlacklist;
import org.unigrid.hedgehog.model.spork.LedgerFee;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StatisticsPubKey;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;
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
	private static final String NODE = "/node";
	private static final String STOP_FIRST = "note: stop the node of this machine before the import, as it keeps its"
		+ " sporks in memory and writes them over the file when it saves";
	private static final Pattern LOGBACK_STATUS = Pattern.compile("\\d{2}:\\d{2}:\\d{2},\\d{3} \\|-");

	@SneakyThrows
	private static Signature keyPair() {
		return new Signature();
	}

	private static final ExclusiveLock.Locker FREE = (file, channel) -> channel.tryLock();
	private static volatile ExclusiveLock.Locker locker = FREE;

	/* The environment gives no key, and the network keys are those of the board of the run */
	@BeforeTry
	public void fakeTheBoard() {
		locker = FREE;

		new MockUp<BoardKeyOptions>() {
			@Mock String environmentKey() {
				return null;
			}
		};

		OptionsCli.parse(List.of("util"), BOARD);
	}

	/* The import installs into the data directory of a node in memory, whose file system gives everyone a lock, so
	   whether the node holds it is said here */
	private static CommandLine.IFactory factory(FileSystem fs) {
		return new CommandLine.IFactory() {
			@Override
			public <K> K create(Class<K> type) throws Exception {
				return type == SporkDbImport.class ? type.cast(new SporkDbImport(() -> fs.getPath(NODE), locker))
					: CommandLine.defaultFactory().create(type);
			}
		};
	}

	private static HedgehogCli.Result run(FileSystem fs, String... args) {
		final List<String> arguments = new ArrayList<>(List.of("util", "spork-db"));

		arguments.addAll(Arrays.asList(args));
		arguments.add(BOARD);

		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class, factory(fs))
			.registerConverter(Path.class, fs::getPath)
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
				"LEDGER_BLACKLIST: absent", "LEDGER_FEE: absent", "STATISTICS_PUBKEY: absent", "BOARD_KEYS: absent",
				"valid: no")));
		}
	}

	/* A link at the output, even one that leads nowhere, is no place to write to: what it leads to could be anything */
	@Example
	@SneakyThrows
	public void refusesAnOutputThatIsALink() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			Files.createSymbolicLink(fs.getPath("/out.db"), fs.getPath("/elsewhere.db"));

			final HedgehogCli.Result result = sign(fs, fixture(fs, LEGACY), "/out.db", FIRST);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--output /out.db exists and is not overwritten"));
			assertThat(result.out(), not(containsString("data:")));
			assertThat(Files.isSymbolicLink(fs.getPath("/out.db")), is(true));
			assertThat(Files.exists(fs.getPath("/elsewhere.db")), is(false));
		}
	}

	/* Nothing is left at the output when the signing fails after it was made */
	@Example
	@SneakyThrows
	public void leavesNoOutputWhenItSignsNothing() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = sign(fs, written(fs, "once.db", supply(supplyOf(10), FIRST)),
				"/out.db", FIRST);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(Files.exists(fs.getPath("/out.db"), LinkOption.NOFOLLOW_LINKS), is(false));
		}
	}

	/* The mints of the fixture are made up, but they are history the network carried, which signing again leaves
	   as it is, so only who signed them fails */
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
				+ ", log entries 0, not signed by the board, content valid"));
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

	/* An amount beyond the bounds of the wire writes out as up to gigabytes, so it is refused before any spork of the
	   file is digested, encoded or shown: the one that is signed and the one before it alike */
	@Example
	@SneakyThrows
	public void refusesAFileThatHoldsAnAmountBeyondTheBoundsOfTheWire() {
		for (String amount : List.of("1E+20", "1E+999999999")) {
			final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());
			final MintSupply.SporkData beyond = new MintSupply.SporkData();

			beyond.setMaxSupply(new BigDecimal(amount));
			spork.setData(supplyOf(10));
			spork.setPreviousData(beyond);

			try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
				final String file = written(fs, "beyond.db", spork).toString();

				for (HedgehogCli.Result result : List.of(run(fs, "validate", file), run(fs, "import", file),
					sign(fs, fs.getPath(file), "/out.db", FIRST))) {

					assertThat(amount, result.exitCode(), equalTo(2));
					assertThat(amount, result.err(), containsString("Cannot read the spork database " + file
						+ ": the MINT_SUPPLY spork holds an amount beyond the bounds of the wire"));
					assertThat(amount, Files.exists(fs.getPath("/out.db")), is(false));
					assertThat(amount, Files.exists(fs.getPath(NODE)), is(false));
				}
			}
		}
	}

	@SneakyThrows
	private static GridSpork blacklist(Set<String> hashes) {
		final GridSpork spork = GridSpork.nextVersion(Type.LEDGER_BLACKLIST, null, Optional.empty());

		spork.<LedgerBlacklist.SporkData>getData().setLegacyAddresses(hashes);
		spork.sign(FIRST.getPrivateKey());
		spork.cosign(SECOND.getPrivateKey());
		return spork;
	}

	/* A file of the database as it was written, with every occurrence of one text in it replaced by another */
	@SneakyThrows
	private static Path writtenWith(FileSystem fs, SporkDatabase database, String text, String replacement) {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (ObjectOutputStream stream = new ObjectOutputStream(bytes)) {
			stream.writeObject(database);
		}

		final String latin = bytes.toString(StandardCharsets.ISO_8859_1).replace(text, replacement);
		final Path file = fs.getPath("/crafted.db");

		Files.write(file, latin.getBytes(StandardCharsets.ISO_8859_1));
		return file;
	}

	/* A set read from a file takes its entries as they come, the same one twice among them, which the wire form
	   cannot hold: a node would read back another spork than the one signed */
	@Example
	@SneakyThrows
	public void refusesASporkThatDoesNotReadBackTheSameFromItsWireForm() {
		final String first = "a".repeat(2 * LedgerBlacklist.SporkData.HASH_BYTES);
		final String second = "b".repeat(2 * LedgerBlacklist.SporkData.HASH_BYTES);
		final SporkDatabase database = SporkDatabase.builder().build();

		database.set(blacklist(new TreeSet<>(Set.of(first, second))));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = writtenWith(fs, database, second, first);
			final HedgehogCli.Result validate = run(fs, "validate", file.toString());
			final HedgehogCli.Result signing = sign(fs, file, "/out.db", FIRST);
			final HedgehogCli.Result importing = run(fs, "import", file.toString());

			assertThat(validate.exitCode(), equalTo(1));
			assertThat(lineOf(validate, Type.LEDGER_BLACKLIST), containsString("content not valid: it does not"
				+ " read back the same from its wire form"));
			assertThat(signing.exitCode(), equalTo(2));
			assertThat(signing.err(), containsString("does not read back the same"));
			assertThat(importing.exitCode(), equalTo(1));
			assertThat(Files.exists(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE)), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAStatisticsKeyThatHoldsAZero() {
		final GridSpork spork = GridSpork.nextVersion(Type.STATISTICS_PUBKEY, null, Optional.empty());

		spork.<StatisticsPubKey.SporkData>getData().setPublicKey("ab\u0000cd");
		spork.sign(FIRST.getPrivateKey());
		spork.cosign(SECOND.getPrivateKey());

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result validate = run(fs, "validate", written(fs, "zero.db", spork).toString());

			assertThat(validate.exitCode(), equalTo(1));
			assertThat(lineOf(validate, Type.STATISTICS_PUBKEY), containsString("content not valid"));
		}
	}

	/* What is installed is what a node reads from the wire, in the form its decoders give it */
	@Example
	@SneakyThrows
	public void importsTheSporksAsTheyReadBackFromTheWire() {
		final SporkDatabase database = SporkDatabase.builder().build();

		database.set(blacklist(new HashSet<>(Set.of("c".repeat(2 * LedgerBlacklist.SporkData.HASH_BYTES)))));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = fs.getPath("/hashed.db");

			SporkDatabase.persist(file, database);

			final HedgehogCli.Result result = run(fs, "import", file.toString());
			final GridSpork installed = load(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE)).getLedgerBlacklist();

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(installed.<LedgerBlacklist.SporkData>getData().getLegacyAddresses(), instanceOf(TreeSet.class));
			assertThat(installed.isTakenByTheNetwork(), is(true));
		}
	}

	/* Vestings that a database holds to an address with a character in it that the terminal does not show */
	@SneakyThrows
	private static Path vestingTo(FileSystem fs, String address) {
		final GridSpork spork = GridSpork.nextVersion(Type.VESTING_STORAGE, null, Optional.empty());

		spork.<VestingStorage.SporkData>getData().getVestingAddresses().put(new Address(address), Vesting.builder()
			.amount(BigDecimal.TEN).start(Instant.EPOCH).duration(Duration.ofDays(30)).parts(4).build());
		spork.sign(keyPair().getPrivateKey());
		return written(fs, "vesting.db", spork);
	}

	/* What is signed is what is shown: a character that the terminal would leave out is shown as its escape */
	@Example
	@SneakyThrows
	public void showsACharacterThatTheTerminalDoesNotShowAsItsEscape() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final String address = LegacyAddress.encode(new byte[20]);
			final HedgehogCli.Result result = sign(fs, vestingTo(fs, address + "​"), "/out.db", FIRST);

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(result.out(), containsStringIgnoringCase(address + "\\u200b"));
		}
	}

	/* A character that the terminal leaves out and that no escape shows makes what is shown another text than what
	   is signed */
	@Example
	@SneakyThrows
	public void signsNothingThatWouldBeShownAsAnotherText() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = sign(fs, vestingTo(fs, LegacyAddress.encode(new byte[20]) + "\u007f"),
				"/out.db", FIRST);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Nothing was signed: the VESTING_STORAGE spork holds text that"
				+ " cannot be shown as it is"));
			assertThat(result.out(), not(containsString("data:")));
			assertThat(Files.exists(fs.getPath("/out.db"), LinkOption.NOFOLLOW_LINKS), is(false));
		}
	}

	/* Signed by the board as a fee, but held where the mints are, so a node would read it as mints */
	@Example
	@SneakyThrows
	public void refusesAFileThatHoldsASporkInTheSlotOfAnotherType() {
		final MintStorage feeAsMints = new MintStorage();
		final SporkDatabase database = SporkDatabase.builder().build();

		feeAsMints.setType(Type.LEDGER_FEE);
		feeAsMints.setData(new LedgerFee.SporkData());
		feeAsMints.archive();
		feeAsMints.sign(FIRST.getPrivateKey());
		feeAsMints.cosign(SECOND.getPrivateKey());
		database.setMintStorage(feeAsMints);

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = fs.getPath("/misplaced.db");

			SporkDatabase.persist(file, database);

			for (HedgehogCli.Result result : List.of(run(fs, "validate", file.toString()),
				run(fs, "import", file.toString()), sign(fs, file, "/out.db", FIRST))) {

				assertThat(result.exitCode(), equalTo(2));
				assertThat(result.err(), containsString("Cannot read the spork database /misplaced.db: the"
					+ " MINT_STORAGE slot holds a spork that is no MINT_STORAGE spork"));
				assertThat(Files.exists(fs.getPath("/out.db")), is(false));
				assertThat(Files.exists(fs.getPath(NODE)), is(false));
			}
		}
	}

	/* A crafted file can name a class whose readObject runs code on the machine that holds the board key */
	@Example
	@SneakyThrows
	public void refusesAFileThatHoldsAClassThatNoSporkDatabaseHolds() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			try (OutputStream out = Files.newOutputStream(fs.getPath("/queue.db"));
				ObjectOutputStream stream = new ObjectOutputStream(out)) {

				stream.writeObject(new PriorityQueue<>(List.of(1, 2)));
			}

			final HedgehogCli.Result result = run(fs, "validate", "/queue.db");

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Cannot read the spork database /queue.db: java.util.PriorityQueue"
				+ " is no class of a spork database"));
			result.assertNoStackTrace();
		}
	}

	private static HedgehogCli.Result sign(FileSystem fs, Path file, String output, Signature key) {
		return run(fs, "sign", file.toString(), "--output", output, "-k", key.getPrivateKey());
	}

	private static List<GridSpork> mintsOf(SporkDatabase database) {
		return List.of(database.getMintStorage(), database.getMintSupply());
	}

	/* What the board signed in 2023 and 2024, with keys it no longer holds, becomes first versions of the same data
	   that two board members sign one after the other, each on a machine of their own */
	@Example
	@SneakyThrows
	public void makesFirstVersionsOfWhatNoBoardKeySignedAndCoSignsThemOnASecondRun() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = fixture(fs, LEGACY);
			final SporkDatabase original = load(file);
			final HedgehogCli.Result first = sign(fs, file, "/once.db", FIRST);

			assertThat(first.err(), first.exitCode(), equalTo(0));
			assertThat(load(file), equalTo(original));

			final SporkDatabase once = load(fs.getPath("/once.db"));

			for (GridSpork spork : mintsOf(once)) {
				final GridSpork before = original.get(spork.getType());

				assertThat(spork.getData(), equalTo(before.getData()));
				assertThat(spork.getPreviousTimeStamp(), equalTo(Instant.EPOCH));
				assertThat(spork.getSignatureLog().isEmpty(), is(true));
				assertThat(spork.getTimeStamp(), greaterThan(before.getTimeStamp()));
				assertThat(spork.isPending(), is(true));
				assertThat(NetworkKey.signerOf(spork), equalTo(Optional.of(FIRST.getPublicKey())));
				assertThat(linesOf(first), hasItem(spork.getType() + ": a first version of its data, made at "
					+ spork.getTimeStamp() + " and signed by " + shortly(FIRST.getPublicKey())));
				assertThat(linesOf(first), hasItems(spork.getType() + " time stamp: " + spork.getTimeStamp(),
					spork.getType() + " flags: " + spork.getFlags()));
			}

			assertThat(first.out(), containsString("MINT_SUPPLY data:"));
			assertThat(first.out(), containsString("\"maxSupply\" : 1000000000"));
			assertThat(linesOf(first), hasItem("output: /once.db"));

			final HedgehogCli.Result second = sign(fs, fs.getPath("/once.db"), "/twice.db", SECOND);

			assertThat(second.err(), second.exitCode(), equalTo(0));

			for (GridSpork spork : mintsOf(load(fs.getPath("/twice.db")))) {
				assertThat(spork.isTakenByTheNetwork(), is(true));
				assertThat(spork.canReplace(null), is(true));
				assertThat(spork.getData(), equalTo(original.get(spork.getType()).getData()));
				assertThat(linesOf(second), hasItem(spork.getType() + ": co-signed by "
					+ shortly(SECOND.getPublicKey())));
			}
		}
	}

	@Example
	@SneakyThrows
	public void leavesASporkThatTheBoardSignedAsItIs() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final GridSpork spork = SerializationUtils.clone(supply(supplyOf(10), FIRST, SECOND));

			spork.renew();
			spork.sign(SECOND.getPrivateKey());
			spork.cosign(FIRST.getPrivateKey());

			final HedgehogCli.Result result = sign(fs, written(fs, "signed.db", spork), "/out.db", FIRST);
			final GridSpork signed = load(fs.getPath("/out.db")).getMintSupply();

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(signed.getSignature(), equalTo(spork.getSignature()));
			assertThat(signed.getCosignature(), equalTo(spork.getCosignature()));
			assertThat(linesOf(result), hasItems("MINT_SUPPLY signature log: 1 entries, head "
				+ shortly(Hex.encodeHexString(spork.getSignatureLog().headHash())),
				"MINT_SUPPLY: signed by the board, left as it is"));
		}
	}

	/* Whoever signs vouches for every byte of it, the previous version and the history among them */
	@Example
	@SneakyThrows
	public void showsEverythingThatItSignsBeforeItSigns() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = written(fs, "once.db", supply(supplyOf(10), FIRST));
			final GridSpork spork = load(file).getMintSupply();
			final HedgehogCli.Result result = sign(fs, file, "/twice.db", SECOND);

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(linesOf(result), hasItems("MINT_SUPPLY time stamp: " + spork.getTimeStamp(),
				"MINT_SUPPLY previous time stamp: 1970-01-01T00:00:00Z", "MINT_SUPPLY flags: " + spork.getFlags(),
				"MINT_SUPPLY signature log: 0 entries, head none",
				"MINT_SUPPLY first signer: " + shortly(FIRST.getPublicKey())));
			assertThat(result.out(), containsString("MINT_SUPPLY data:\n{\n  \"maxSupply\" : 10\n}"));
			assertThat(result.out(), containsString("MINT_SUPPLY previous data:\n{\n  \"maxSupply\" : 0\n}"));
		}
	}

	@Example
	@SneakyThrows
	public void refusesToCoSignWithTheKeyThatSigned() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = sign(fs, written(fs, "once.db", supply(supplyOf(10), FIRST)),
				"/out.db", FIRST);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("A second, different network key has to co-sign the spork"));
			result.assertNoStackTrace();
			assertThat(Files.exists(fs.getPath("/out.db")), is(false));
		}
	}

	/* Signed once by a board key, but not as this command makes a first version: a proposal over a version that the
	   file does not hold, data before it that was never shown as a change, a history, or the form of a build from
	   before co-signatures. A co-signature would make any of them a spork that the network takes. */
	@SneakyThrows
	private static Map<String, GridSpork> signedOnceButNoFirstVersion() {
		final GridSpork stored = supply(supplyOf(10), FIRST, SECOND);
		final GridSpork overStored = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());
		final GridSpork withPreviousData = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());
		final GridSpork withALog = SerializationUtils.clone(stored);
		final GridSpork ofAnEarlierBuild = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		overStored.setData(supplyOf(20));
		overStored.sign(FIRST.getPrivateKey());
		withPreviousData.setData(supplyOf(10));
		withPreviousData.setPreviousData(supplyOf(1_000_000));
		withPreviousData.sign(FIRST.getPrivateKey());
		withALog.renew();
		withALog.sign(FIRST.getPrivateKey());
		ofAnEarlierBuild.setData(supplyOf(10));
		ofAnEarlierBuild.setFlags((short) 0);
		ofAnEarlierBuild.setSignature(FIRST.sign(ofAnEarlierBuild.getSignable()));

		return Map.of("a version over another", overStored, "previous data", withPreviousData,
			"a signature log", withALog, "an earlier build", ofAnEarlierBuild);
	}

	@Example
	@SneakyThrows
	public void coSignsNothingSignedOnceThatIsNoFirstVersionOfItsOwn() {
		signedOnceButNoFirstVersion().forEach((how, spork) -> {
			try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
				final HedgehogCli.Result result = sign(fs, written(fs, "once.db", spork), "/out.db", SECOND);

				assertThat(how, result.exitCode(), equalTo(2));
				assertThat(how, result.err(), containsString("Nothing was signed: the MINT_SUPPLY spork is signed"
					+ " once and is no first version of its data"));
				assertThat(how, result.err(), containsString("gridspork-cosign"));
				assertThat(how, Files.exists(fs.getPath("/out.db")), is(false));
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});
	}

	/* A first version signed once by the first board key, made at the time given */
	@SneakyThrows
	private static GridSpork firstVersionMadeAt(Instant at) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.setData(supplyOf(10));
		spork.setTimeStamp(at);
		spork.sign(FIRST.getPrivateKey());
		return spork;
	}

	/* Stamped ahead, a spork would win over every version the board makes until that time comes */
	@Example
	@SneakyThrows
	public void signsNothingStampedAheadOfTheClock() {
		final Instant ahead = Instant.parse("2100-01-01T00:00:00Z");
		final GridSpork unsigned = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		unsigned.setData(supplyOf(10));
		unsigned.setTimeStamp(ahead);
		unsigned.sign(keyPair().getPrivateKey());

		final Map<String, GridSpork> stampedAhead = Map.of("no board key signed", unsigned,
			"a first version to co-sign", firstVersionMadeAt(Instant.now().truncatedTo(ChronoUnit.MILLIS)
				.plus(PendingSporks.LEEWAY).plusSeconds(60)));

		stampedAhead.forEach((how, spork) -> {
			try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
				final HedgehogCli.Result result = sign(fs, written(fs, "ahead.db", spork), "/out.db", SECOND);

				assertThat(how, result.exitCode(), equalTo(2));
				assertThat(how, result.err(), containsString("Nothing was signed: the MINT_SUPPLY spork is stamped at "
					+ spork.getTimeStamp() + ", ahead of the clock of this machine"));
				assertThat(how, Files.exists(fs.getPath("/out.db")), is(false));
			} catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		});
	}

	/* Board members pass the file between them, which can take longer than a proposal waits on a node */
	@Example
	@SneakyThrows
	public void coSignsAFirstVersionMadeLongAgoAndShowsItsAge() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final GridSpork spork = firstVersionMadeAt(Instant.now().truncatedTo(ChronoUnit.MILLIS)
				.minus(Duration.ofHours(3)));
			final HedgehogCli.Result result = sign(fs, written(fs, "once.db", spork), "/out.db", SECOND);

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(linesOf(result), hasItem(startsWith("MINT_SUPPLY age: PT3H")));
			assertThat(load(fs.getPath("/out.db")).getMintSupply().isTakenByTheNetwork(), is(true));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyThatIsNoBoardKey() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = sign(fs, fixture(fs, LEGACY), "/out.db", keyPair());

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("not one of the current board keys"));
			assertThat(result.out(), emptyString());
			assertThat(Files.exists(fs.getPath("/out.db")), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAnOutputThatExists() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			Files.writeString(fs.getPath("/out.db"), "another database");

			final HedgehogCli.Result result = sign(fs, fixture(fs, LEGACY), "/out.db", FIRST);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--output /out.db exists and is not overwritten"));
			assertThat(Files.readString(fs.getPath("/out.db")), equalTo("another database"));
		}
	}

	/* The mints of the fixture are made up, so nothing of it is signed and nothing of it is shown as signed */
	@Example
	@SneakyThrows
	public void signsNothingWhenTheContentOfASporkDoesNotHold() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final GridSpork unsigned = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

			unsigned.setData(supplyOf(-1));

			final Path negative = written(fs, "negative.db", unsigned);
			final HedgehogCli.Result result = sign(fs, negative, "/out.db", FIRST);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Nothing was signed: the content of the MINT_SUPPLY spork does"
				+ " not hold: A maximum supply is not negative"));
			assertThat(result.out(), not(containsString("data:")));
			assertThat(Files.exists(fs.getPath("/out.db")), is(false));
		}
	}

	/* The database a node starts with, as an earlier build left it */
	@SneakyThrows
	private static byte[] nodeHolding(FileSystem fs, Path database) {
		final byte[] held = Files.readAllBytes(database);

		Files.createDirectories(fs.getPath(NODE));
		Files.write(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE), held);
		return held;
	}

	@SneakyThrows
	private static List<Path> backupsIn(FileSystem fs) {
		try (Stream<Path> files = Files.list(fs.getPath(NODE))) {
			return files.filter(file -> file.getFileName().toString().matches("spork\\.db\\.\\d+")).toList();
		}
	}

	@Example
	@SneakyThrows
	public void importsADatabaseThatTheBoardSignedAndKeepsTheOneItReplaces() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final byte[] held = nodeHolding(fs, fixture(fs, LEGACY));
			final GridSpork spork = supply(supplyOf(10), FIRST, SECOND);
			final HedgehogCli.Result result = run(fs, "import", written(fs, "signed.db", spork).toString());
			final List<Path> backups = backupsIn(fs);

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(load(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE)).getMintSupply().getSignature(),
				equalTo(spork.getSignature()));
			assertThat(backups, hasSize(1));
			assertThat(Files.readAllBytes(backups.getFirst()), equalTo(held));
			assertThat(linesOf(result), hasItems(STOP_FIRST, "valid: yes", "backup: " + backups.getFirst(),
				"imported: /node/spork.db"));
		}
	}

	@Example
	@SneakyThrows
	public void importsADatabaseIntoANodeThatHoldsNoneYet() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, "import", written(fs, "signed.db",
				supply(supplyOf(10), FIRST, SECOND)).toString());

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(Files.exists(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE)), is(true));
			assertThat(backupsIn(fs), hasSize(0));
			assertThat(linesOf(result), hasItem("backup: none, the node holds no spork database"));
		}
	}

	/* A node would drop such a spork on start, so it is not installed at all */
	@Example
	@SneakyThrows
	public void importsNothingThatTheBoardDidNotSign() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final byte[] held = nodeHolding(fs, fixture(fs, RELEASE_007));
			final HedgehogCli.Result once = run(fs, "import", written(fs, "once.db",
				supply(supplyOf(10), FIRST)).toString());
			final HedgehogCli.Result negative = run(fs, "import", written(fs, "negative.db",
				supply(supplyOf(-1), FIRST, SECOND)).toString());

			for (HedgehogCli.Result result : List.of(once, negative)) {
				assertThat(result.exitCode(), equalTo(1));
				assertThat(linesOf(result), hasItem("valid: no"));
				assertThat(result.err(), containsString("Nothing was imported"));
			}

			assertThat(Files.readAllBytes(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE)), equalTo(held));
			assertThat(backupsIn(fs), hasSize(0));
		}
	}

	/* A running node writes its own database over the one installed when it saves */
	@Example
	@SneakyThrows
	public void importsNothingWhileANodeHoldsItsSporkDatabase() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final byte[] held = nodeHolding(fs, fixture(fs, LEGACY));

			locker = (file, channel) -> null;

			final HedgehogCli.Result result = run(fs, "import", written(fs, "signed.db",
				supply(supplyOf(10), FIRST, SECOND)).toString());

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Nothing was imported: the spork database in /node is in use"));
			result.assertNoStackTrace();
			assertThat(Files.readAllBytes(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE)), equalTo(held));
			assertThat(backupsIn(fs), hasSize(0));
		}
	}

	@Example
	@SneakyThrows
	public void importsNothingFromAFileThatCannotBeRead() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, "import", "/missing.db");

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Cannot read the spork database /missing.db"));
			assertThat(Files.exists(fs.getPath(NODE)), is(false));
		}
	}

	/* The step of the launch: the database of the node that holds the sporks of 2023 and 2024 is checked, signed by
	   two board members one after the other, checked again and installed */
	@Example
	@SneakyThrows
	public void takesTheSporksOfAnEarlierBuildThroughTheLaunch() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path earlier = fixture(fs, LEGACY);
			final byte[] held = nodeHolding(fs, earlier);
			final HedgehogCli.Result before = run(fs, "validate", earlier.toString());
			final HedgehogCli.Result first = sign(fs, earlier, "/spork.db.once", FIRST);
			final HedgehogCli.Result second = sign(fs, fs.getPath("/spork.db.once"), "/spork.db.signed", SECOND);
			final HedgehogCli.Result after = run(fs, "validate", "/spork.db.signed");
			final HedgehogCli.Result imported = run(fs, "import", "/spork.db.signed");
			final SporkDatabase installed = load(fs.getPath(NODE, SporkDatabase.SPORK_DB_FILE));

			assertThat(before.exitCode(), equalTo(1));
			assertThat(lineOf(before, Type.MINT_STORAGE), containsString("not signed by the board"));
			assertThat(lineOf(before, Type.MINT_SUPPLY), containsString("not signed by the board"));
			assertThat(first.err(), first.exitCode(), equalTo(0));
			assertThat(second.err(), second.exitCode(), equalTo(0));
			assertThat(after.err(), after.exitCode(), equalTo(0));
			assertThat(imported.err(), imported.exitCode(), equalTo(0));
			assertThat(Files.readAllBytes(backupsIn(fs).getFirst()), equalTo(held));

			for (GridSpork spork : mintsOf(installed)) {
				assertThat(spork.isTakenByTheNetwork(), is(true));
				assertThat(spork.getData(), equalTo(load(earlier).get(spork.getType()).getData()));
			}
		}
	}

	/* Entries that a new version could not add, such as mints of addresses no ledger knows, are history here */
	@Example
	@SneakyThrows
	public void signsTheHistoryOfAnEarlierBuildAsItIs() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path earlier = fixture(fs, RELEASE_007);
			final HedgehogCli.Result first = sign(fs, earlier, "/spork.db.once", FIRST);
			final HedgehogCli.Result second = sign(fs, fs.getPath("/spork.db.once"), "/spork.db.signed", SECOND);
			final GridSpork mints = load(fs.getPath("/spork.db.signed")).getMintStorage();

			assertThat(first.err(), first.exitCode(), equalTo(0));
			assertThat(second.err(), second.exitCode(), equalTo(0));
			assertThat(mints.isTakenByTheNetwork(), is(true));
			assertThat(mints.getData(), equalTo(load(earlier).getMintStorage().getData()));
		}
	}
}
