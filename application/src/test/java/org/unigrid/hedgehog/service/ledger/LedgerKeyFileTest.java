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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.KEY_FILE;
import static org.unigrid.hedgehog.service.ledger.LedgerKeyFixtures.OWNER_ONLY;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.ledger.AccountKey;
import org.unigrid.hedgehog.ledger.Ed25519;

public class LedgerKeyFileTest {
	private static final int HEX_SIZE = Ed25519.SEED_SIZE * 2;
	private static final int MAX_BYTES = 128;
	private static final int MEBIBYTE = 1 << 20;
	private static final byte[] MESSAGE = "a block".getBytes(StandardCharsets.UTF_8);

	@Provide
	public Arbitrary<byte[]> provideSeed() {
		return Arbitraries.bytes().array(byte[].class).ofSize(Ed25519.SEED_SIZE);
	}

	@Provide
	public Arbitrary<String> provideLineEnding() {
		return Arbitraries.of("", "\n", "\r\n", "\n\n", " \n");
	}

	@Provide
	public Arbitrary<Set<PosixFilePermission>> providePermissions() {
		return Arbitraries.of(PosixFilePermission.class).set();
	}

	@Provide
	public Arbitrary<UnaryOperator<String>> provideDamage() {
		return Arbitraries.of(
			hex -> hex.substring(1),
			hex -> hex + "0",
			hex -> hex.substring(0, HEX_SIZE / 2),
			hex -> "g" + hex.substring(1),
			hex -> hex.substring(0, HEX_SIZE - 1) + "z",
			hex -> "0x" + hex.substring(2),
			hex -> hex.substring(0, HEX_SIZE / 2) + " " + hex.substring(HEX_SIZE / 2 + 1),
			hex -> hex + "\n" + hex,
			hex -> hex + "\nmore",
			hex -> hex + " trailing",
			hex -> "",
			hex -> "\n",
			hex -> "   "
		);
	}

	private static String messageOfLoading(Path file) {
		return assertThrows(IllegalArgumentException.class, () -> LedgerKeyFile.load(file)).getMessage();
	}

	@SneakyThrows
	private static Set<PosixFilePermission> permissionsOf(Path file) {
		return Files.getPosixFilePermissions(file);
	}

	@SneakyThrows
	private static List<String> entriesOf(Path directory) {
		try (Stream<Path> entries = Files.list(directory)) {
			return entries.map(Path::toString).sorted().toList();
		}
	}

	private static boolean reachableByOthers(Set<PosixFilePermission> permissions) {
		return permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
			|| permission.name().startsWith("OTHERS_"));
	}

	/* A pipe would be read for ever, so what is no regular file is refused before it is opened; a directory stands in
	   for it here, as no pipe can be made in memory */
	@Example
	@SneakyThrows
	public void shouldRefuseAKeyFileThatIsNoRegularFileBeforeOpeningIt() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			Files.createDirectories(fs.getPath(KEY_FILE));

			assertThat(messageOfLoading(fs.getPath(KEY_FILE)), containsString(KEY_FILE));
			assertThat(messageOfLoading(fs.getPath(KEY_FILE)), containsString("regular file"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseALinkToWhatIsNoRegularFile() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			Files.createDirectories(fs.getPath("/elsewhere"));
			Files.createSymbolicLink(fs.getPath("/key-link"), fs.getPath("/elsewhere"));

			assertThat(messageOfLoading(fs.getPath("/key-link")), containsString("regular file"));
		}
	}

	/* A process that dies between writing the temporary file and linking it leaves the file with the seed behind */
	@Example
	@SneakyThrows
	public void shouldRemoveTheTemporaryFilesOfEarlierCreationsOlderThanAMinuteAndNothingElse() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path directory = fs.getPath("/keys");
			final FileTime old = FileTime.from(Instant.now().minus(Duration.ofMinutes(2)));
			final List<String> stale = List.of("ledger-key1234567890123.tmp", "ledger-key7.tmp");
			final List<String> kept = List.of("ledger-key55.tmp", "ledger-key-backup.tmp", "ledger-key55.txt",
				"other.tmp", "ledger.key.old");

			Files.createDirectories(directory);

			for (final String name : Stream.concat(stale.stream(), kept.stream()).toList()) {
				Files.writeString(directory.resolve(name), "x");
				Files.setLastModifiedTime(directory.resolve(name), old);
			}

			Files.setLastModifiedTime(directory.resolve("ledger-key55.tmp"), FileTime.from(Instant.now()));
			LedgerKeyFile.create(fs.getPath(KEY_FILE));

			final List<String> left = Files.list(directory).map(entry -> entry.getFileName().toString()).toList();

			assertThat(left, containsInAnyOrder(Stream.concat(kept.stream(), Stream.of("ledger.key")).toArray()));
		}
	}

	@Property(tries = 30)
	@SneakyThrows
	public void shouldLoadTheSeedOfAFileOfSixtyFourHexCharacters(@ForAll("provideSeed") byte[] seed,
		@ForAll boolean upperCase, @ForAll("provideLineEnding") String ending) {

		final String hex = HexFormat.of().formatHex(seed);

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, (upperCase ? hex.toUpperCase() : hex) + ending,
				OWNER_ONLY
			);

			assertThat(LedgerKeyFile.load(file), equalTo(seed));
		}
	}

	@Property(tries = 60)
	@SneakyThrows
	public void shouldRefuseWhatIsNotOneLineOfSixtyFourHexCharactersWithoutRevealingIt(
		@ForAll("provideSeed") byte[] seed, @ForAll("provideDamage") UnaryOperator<String> damage) {

		final String hex = HexFormat.of().formatHex(seed);

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, damage.apply(hex) + "\n", OWNER_ONLY);
			final String message = messageOfLoading(file);

			assertThat(message, containsString(KEY_FILE));
			assertThat(message, not(containsString(hex.substring(2, HEX_SIZE / 2))));
		}
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldTakeAFileOfAtMostOneHundredAndTwentyEightBytes(@ForAll("provideSeed") byte[] seed,
		@ForAll @IntRange(min = 0, max = MAX_BYTES - HEX_SIZE - 1) int padding) {

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final String hex = HexFormat.of().formatHex(seed);
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, hex + "\n" + " ".repeat(padding), OWNER_ONLY);

			assertThat(LedgerKeyFile.load(file), equalTo(seed));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileOfOneByteMoreThanThat() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final String hex = "ab".repeat(Ed25519.SEED_SIZE);
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, hex + "\n" + " ".repeat(MAX_BYTES - HEX_SIZE),
				OWNER_ONLY
			);

			assertThat(Files.size(file), equalTo((long) MAX_BYTES + 1));
			assertThat(messageOfLoading(file), containsString(KEY_FILE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileOfOneMebibyteThatHoldsASeedFirst() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final String hex = "ab".repeat(Ed25519.SEED_SIZE);
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, hex + "\n" + " ".repeat(MEBIBYTE), OWNER_ONLY);

			final String message = messageOfLoading(file);

			assertThat(message, containsString(KEY_FILE));
			assertThat(message, not(containsString(hex)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseAFileThatIsNotThere() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			assertThat(messageOfLoading(fs.getPath("/nowhere.key")), containsString("/nowhere.key"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseADirectory() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path directory = Files.createDirectories(fs.getPath(KEY_FILE));

			Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
			assertThat(messageOfLoading(directory), containsString(KEY_FILE));
		}
	}

	@Property(tries = 60)
	@SneakyThrows
	public void shouldOnlyLoadAFileThatNoOneButTheOwnerCanReach(@ForAll("provideSeed") byte[] seed,
		@ForAll("providePermissions") Set<PosixFilePermission> permissions) {

		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, HexFormat.of().formatHex(seed) + "\n",
				PosixFilePermissions.toString(permissions)
			);

			if (reachableByOthers(permissions)) {
				final String message = messageOfLoading(file);

				assertThat(message, containsString(KEY_FILE));
				assertThat(message, containsString("chmod 600"));
			} else {
				assertThat(LedgerKeyFile.load(file), equalTo(seed));
			}
		}
	}

	@Example
	@SneakyThrows
	public void shouldRefuseTheModesAnOperatorLeavesBehind() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			for (String mode : List.of("rw-r-----", "rw----r--", "rw-rw----", "rw-r--r--", "rwxrwxrwx")) {
				final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, "0".repeat(HEX_SIZE) + "\n", mode);

				assertThat(mode, messageOfLoading(file), containsString("chmod 600"));
			}

			for (String mode : List.of("rw-------", "r--------")) {
				final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, "0".repeat(HEX_SIZE) + "\n", mode);

				assertThat(mode, LedgerKeyFile.load(file).length, equalTo(Ed25519.SEED_SIZE));
			}
		}
	}

	@Example
	@SneakyThrows
	public void shouldSkipThePermissionCheckWhereThereAreNoPermissions() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, "ab".repeat(Ed25519.SEED_SIZE) + "\n", null);

			assertThat(LedgerKeyFile.load(file), equalTo(HexFormat.of().parseHex("ab".repeat(Ed25519.SEED_SIZE))));
		}
	}

	@Example
	@SneakyThrows
	public void shouldWriteAFreshSeedThatOnlyTheOwnerCanRead() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = fs.getPath(KEY_FILE);

			LedgerKeyFile.create(file);

			assertThat(PosixFilePermissions.toString(permissionsOf(file)), equalTo(OWNER_ONLY));
			assertThat(Files.readString(file), matchesPattern("\\p{XDigit}{" + HEX_SIZE + "}\n"));
			assertThat(LedgerKeyFile.load(file).length, equalTo(Ed25519.SEED_SIZE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldWriteADifferentSeedEachTime() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			LedgerKeyFile.create(fs.getPath("/keys/one.key"));
			LedgerKeyFile.create(fs.getPath("/keys/two.key"));

			assertThat(LedgerKeyFile.load(fs.getPath("/keys/one.key")),
				not(equalTo(LedgerKeyFile.load(fs.getPath("/keys/two.key"))))
			);
		}
	}

	@Example
	@SneakyThrows
	public void shouldLeaveNothingButTheKeyFile() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path directory = Files.createDirectories(fs.getPath("/data/ledger"));

			LedgerKeyFile.create(directory.resolve("validator.key"));
			assertThat(entriesOf(directory), equalTo(List.of("/data/ledger/validator.key")));
		}
	}

	@Example
	@SneakyThrows
	public void shouldCreateTheFileWhereThereAreNoPermissions() {
		try (FileSystem fs = LedgerKeyFixtures.plainFileSystem()) {
			final Path file = fs.getPath("/new/directory/validator.key");

			LedgerKeyFile.create(file);
			assertThat(LedgerKeyFile.load(file).length, equalTo(Ed25519.SEED_SIZE));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNeverOverwriteAnExistingFile() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = LedgerKeyFixtures.write(fs, KEY_FILE, "keep me", OWNER_ONLY);

			assertThat(assertThrows(FileAlreadyExistsException.class, () -> LedgerKeyFile.create(file)).getMessage(),
				containsString(KEY_FILE)
			);
			assertThat(Files.readString(file), equalTo("keep me"));
			assertThat(entriesOf(file.getParent()), equalTo(List.of(KEY_FILE)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNeverOverwriteTheKeyItMadeBefore() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path file = fs.getPath(KEY_FILE);

			LedgerKeyFile.create(file);

			final String first = Files.readString(file);

			assertThrows(FileAlreadyExistsException.class, () -> LedgerKeyFile.create(file));
			assertThat(Files.readString(file), equalTo(first));
			assertThat(entriesOf(file.getParent()), equalTo(List.of(KEY_FILE)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNeverReplaceALinkEvenWhenItPointsNowhere() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path link = fs.getPath(KEY_FILE);

			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, fs.getPath("/elsewhere/missing.key"));

			assertThrows(FileAlreadyExistsException.class, () -> LedgerKeyFile.create(link));
			assertThat(Files.isSymbolicLink(link), equalTo(true));
			assertThat(Files.exists(fs.getPath("/elsewhere")), equalTo(false));
		}
	}

	@Example
	@SneakyThrows
	public void shouldLeaveNoPartialFileWhenTheDirectoryCannotBeMade() {
		try (FileSystem fs = LedgerKeyFixtures.posixFileSystem()) {
			final Path blocker = LedgerKeyFixtures.write(fs, "/data/blocker", "x", OWNER_ONLY);

			assertThrows(IOException.class, () -> LedgerKeyFile.create(blocker.resolve("validator.key")));
			assertThat(entriesOf(blocker.getParent()), equalTo(List.of("/data/blocker")));
		}
	}

	@Property(tries = 30)
	public void shouldDeriveThePublicKeyThatVerifiesWhatTheSeedSigns(@ForAll("provideSeed") byte[] seed) {
		final String hex = LedgerKeyFile.publicKeyHex(seed);

		assertThat(hex, matchesPattern("[0-9a-f]{" + HEX_SIZE + "}"));
		assertThat(hex, equalTo(Ed25519.publicKey(seed).toString()));
		assertThat(Ed25519.verify(AccountKey.fromHex(hex), MESSAGE, Ed25519.sign(seed, MESSAGE)), equalTo(true));
	}

	@Property(tries = 30)
	public void shouldNotMistakeOneSeedForAnother(@ForAll("provideSeed") byte[] seed,
		@ForAll("provideSeed") byte[] other) {

		Assume.that(!Arrays.equals(seed, other));
		assertThat(LedgerKeyFile.publicKeyHex(seed), not(equalTo(LedgerKeyFile.publicKeyHex(other))));
	}
}
