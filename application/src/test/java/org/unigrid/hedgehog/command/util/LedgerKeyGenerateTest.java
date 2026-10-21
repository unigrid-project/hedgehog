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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.OWNER_ONLY;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.Hedgehog;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFile;
import org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures;
import picocli.CommandLine;

public class LedgerKeyGenerateTest {
	private static final String PUBLIC_KEY = "\\p{XDigit}{" + Ed25519.SEED_SIZE * 2 + "}\\R";

	@Provide
	public Arbitrary<byte[]> provideSeed() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Ed25519.SEED_SIZE);
	}

	private static HedgehogCli.Result generateOn(FileSystem fs, String... args) {
		return HedgehogCli.run(() -> new CommandLine(Hedgehog.class).registerConverter(Path.class, fs::getPath),
			Stream.concat(Stream.of("util", "ledger-key-generate"), Stream.of(args)).toArray(String[]::new)
		);
	}

	@Example
	@SneakyThrows
	public void shouldPrintTheOnePublicKeyOfTheSeedItWrites() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final HedgehogCli.Result result = generateOn(fs, KEY_FILE);
			final Path file = fs.getPath(KEY_FILE);

			assertThat(result.exitCode(), equalTo(0));
			assertThat(result.err(), emptyString());
			assertThat(result.out(), matchesPattern(PUBLIC_KEY));
			assertThat(result.out().strip(), equalTo(LedgerKeyFile.publicKeyHex(LedgerKeyFile.load(file))));
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), equalTo(OWNER_ONLY));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNeverPrintTheSeed() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final HedgehogCli.Result result = generateOn(fs, KEY_FILE);
			final String seed = Files.readString(fs.getPath(KEY_FILE)).strip();

			assertThat(seed.length(), equalTo(Ed25519.SEED_SIZE * 2));
			assertThat(result.out(), not(containsString(seed)));
			assertThat(result.err(), not(containsString(seed)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldMakeAnotherKeyEachTime() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			assertThat(generateOn(fs, "/keys/one.key").out(), not(equalTo(generateOn(fs, "/keys/two.key").out())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldWriteWhereTheFileSystemHasNoPermissions() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final HedgehogCli.Result result = generateOn(fs, KEY_FILE);

			assertThat(result.exitCode(), equalTo(0));
			assertThat(result.out(), matchesPattern(PUBLIC_KEY));
		}
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldRefuseToOverwriteAKeyWithoutRevealingIt(@ForAll("provideSeed") byte[] seed) {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = LedgerKeyFixtures.writeSeed(fs, seed);
			final String before = Files.readString(file);
			final HedgehogCli.Result result = generateOn(fs, KEY_FILE);

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Cannot create the ledger key file " + KEY_FILE));
			assertThat(result.err(), not(containsString(HexFormat.of().formatHex(seed))));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
			assertThat(Files.readString(file), equalTo(before));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALocationItCannotWriteTo() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFixtures.write(fs, "/data/blocker", "x", OWNER_ONLY);

			final HedgehogCli.Result result = generateOn(fs, "/data/blocker/validator.key");

			assertThat(result.exitCode(), equalTo(2));
			assertThat(result.err(), containsString("Cannot create the ledger key file /data/blocker/validator.key"));
			result.assertNoStackTrace();
			assertThat(result.out(), emptyString());
		}
	}

	@Example
	public void shouldAskForTheFile() {
		final HedgehogCli.Result result = HedgehogCli.run("util", "ledger-key-generate");

		assertThat(result.exitCode(), equalTo(2));
		assertThat(result.err(), containsString("Missing required parameter"));
		result.assertNoStackTrace();
		assertThat(result.out(), emptyString());
	}
}
