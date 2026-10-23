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
import static org.hamcrest.Matchers.notNullValue;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.model.bootstrap.AddressSnapshots;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDigest;
import org.unigrid.hedgehog.model.bootstrap.SnapshotReader;
import org.unigrid.hedgehog.service.ledger.GenesisFile;
import org.unigrid.hedgehog.service.ledger.SnapshotCheck;
import picocli.CommandLine;

public class LedgerGenesisCommandTest {
	private static final String SNAPSHOT = "/snapshot.dat";
	private static final String OUTPUT = "/ledger-genesis.json";
	/* The public keys of the seeds 1 and 2 */
	private static final String VALIDATOR = "b1f804dabc68274c1233995c5a9119b56935bcdd83b7de07ec726dcedc4e9ce7";
	private static final String OTHER_VALIDATOR = "6a0f6a283a8e4e86d2a3d60ef9e37ec33f2ab6071a30e0a477735128e7571eb0";
	private static final String NO_POINT = "02" + "00".repeat(AccountKey.SIZE - 1);
	private static final long TIME = 1_700_000_000_000L;
	private static final long ROUND_MILLIS = 3_600_000L;
	private static final long UNITS = 5_000L;
	private static final HexFormat HEX = HexFormat.of();

	private static HedgehogCli.Result run(FileSystem fs, Path snapshot, long roundMillis, String... options) {
		return run(fs, snapshot, TIME, roundMillis, options);
	}

	private static HedgehogCli.Result run(FileSystem fs, Path snapshot, long time, long roundMillis,
		String... options) {

		final List<String> args = new ArrayList<>(List.of("util", "ledger-genesis", snapshot.toString(),
			"--time", String.valueOf(time), "--round-millis", String.valueOf(roundMillis), "--output",
			OUTPUT));

		args.addAll(List.of(options));

		/* The snapshot must stay a real file because the reader maps it; everything else stays in memory */
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class,
			name -> name.equals(snapshot.toString()) ? snapshot : fs.getPath(name)),
			args.toArray(String[]::new));
	}

	@Example
	@SneakyThrows
	public void shouldWriteTheGenesisTheNodeChecksTheSnapshotAgainst() {
		final Path snapshot = AddressSnapshots.write(Map.of("11".repeat(20), UNITS));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, snapshot, ROUND_MILLIS, "--validator", VALIDATOR,
				"--validator", OTHER_VALIDATOR);

			assertThat(result.exitCode(), equalTo(0));
			assertThat(result.err(), emptyString());

			final LedgerGenesis genesis = GenesisFile.load(Optional.of(fs.getPath(OUTPUT))).orElseThrow();
			final SnapshotReader reader = SnapshotReader.open(snapshot);
			final byte[] hash = SnapshotDigest.of(snapshot);

			assertThat(genesis.getTime(), equalTo(TIME));
			assertThat(genesis.getRoundMillis(), equalTo(ROUND_MILLIS));
			assertThat(genesis.getValidators().keys(), equalTo(List.of(AccountKey.fromHex(VALIDATOR),
				AccountKey.fromHex(OTHER_VALIDATOR))));
			assertThat(genesis.getSnapshotHash(), equalTo(hash));
			assertThat(genesis.getSnapshotTotal(), equalTo(UNITS));
			assertThat(SnapshotCheck.verify(snapshot, reader, genesis), equalTo(Optional.empty()));
			assertThat(result.out().lines().toList(), equalTo(List.of(
				"snapshotHash: " + HEX.formatHex(hash),
				"snapshotTotal: " + UNITS,
				"validators: 2",
				"chainId: " + GenesisFile.chainIdHex(genesis))));
		}
	}

	/* Opening a snapshot that is signed asks for the network keys, which only a command that takes the option has */
	@Example
	public void shouldTakeTheNetworkKeysToCheckTheSignatureOfASnapshot() {
		final CommandLine util = new CommandLine(Hedgehog.class).getSubcommands().get("util");

		assertThat(util.getCommandSpec().findOption("--network-keys"), notNullValue());
	}

	@Example
	@SneakyThrows
	public void shouldAskForAValidator() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), ROUND_MILLIS);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Missing required option: '--validator"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAValidatorThatIsNot64HexCharacters() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), ROUND_MILLIS,
				"--validator", "xyz");

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--validator must be 64 hexadecimal characters"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAValidatorThatIsNoPublicKey() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), ROUND_MILLIS,
				"--validator", VALIDATOR, "--validator", NO_POINT);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--validator " + NO_POINT + " is not an Ed25519 public key"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseARepeatedValidator() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), ROUND_MILLIS,
				"--validator", VALIDATOR, "--validator", VALIDATOR);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--validator repeats an earlier key"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseToOverwriteTheOutput() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			Files.writeString(fs.getPath(OUTPUT), "the genesis of another chain");

			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), ROUND_MILLIS,
				"--validator", VALIDATOR);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--output " + OUTPUT));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
			assertThat(Files.readString(fs.getPath(OUTPUT)), equalTo("the genesis of another chain"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseATimeBeforeTheEpoch() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), -1L, ROUND_MILLIS,
				"--validator", VALIDATOR);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--time is 0 or more"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseARoundShorterThanAMinute() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, fs.getPath(SNAPSHOT), 59_999, "--validator", VALIDATOR);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("--round-millis is at least 60000, found 59999"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	@SneakyThrows
	public void shouldTakeARoundOfAMinute() {
		final Path snapshot = AddressSnapshots.write(Map.of("11".repeat(20), UNITS));

		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final HedgehogCli.Result result = run(fs, snapshot, 60_000, "--validator", VALIDATOR);

			assertThat(result.err(), result.exitCode(), equalTo(0));
			assertThat(GenesisFile.load(Optional.of(fs.getPath(OUTPUT))).orElseThrow().getRoundMillis(),
				equalTo(60_000L));
		}
	}
}
