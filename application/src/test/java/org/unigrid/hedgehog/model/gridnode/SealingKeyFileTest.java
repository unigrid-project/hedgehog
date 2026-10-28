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

package org.unigrid.hedgehog.model.gridnode;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class SealingKeyFileTest {
	private static final String OWNER_ONLY = "rw-------";
	private static final String DATA_DIRECTORY = "/data";
	private static final String KEY_FILE = DATA_DIRECTORY + "/" + SealingKeyFile.FILE;
	private static final HexFormat HEX = HexFormat.of();

	/* The key pair of RFC 7748, section 6.1 */
	private static final String ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
	private static final String ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";

	/* The plain unix configuration has no permission attributes, which the key file check reads */
	private static FileSystem posixFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setAttributeViews("basic", "owner", "posix", "unix").build());
	}

	@SneakyThrows
	private static Path write(FileSystem fs, String content, String permissions) {
		final Path file = fs.getPath(KEY_FILE);

		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);

		if (permissions != null) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
		}

		return file;
	}

	private static String messageOfLoading(Path file) {
		return assertThrows(IllegalArgumentException.class, () -> SealingKeyFile.loadOrCreate(file)).getMessage();
	}

	@SneakyThrows
	private static List<String> namesIn(Path directory) {
		try (Stream<Path> entries = Files.list(directory)) {
			return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
		}
	}

	@Example
	@SneakyThrows
	public void createsAnOwnerOnlyKeyOnceAndNeverReplacesIt() {
		try (FileSystem fs = posixFileSystem()) {
			final Path file = fs.getPath(KEY_FILE);
			final byte[] created = SealingKeyFile.loadOrCreate(file);
			final String content = Files.readString(file);

			assertThat(created.length, equalTo(SealingKeyFile.KEY_SIZE));
			assertThat(content, matchesPattern("\\p{XDigit}{" + 2 * SealingKeyFile.KEY_SIZE + "}\n"));
			assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), equalTo(OWNER_ONLY));
			assertThat(SealingKeyFile.loadOrCreate(file), equalTo(created));
			assertThat(Files.readString(file), equalTo(content));
			assertThat(namesIn(file.getParent()), equalTo(List.of(SealingKeyFile.FILE)));
		}
	}

	@Example
	@SneakyThrows
	public void keepsTheKeyThatIsThereInsteadOfMakingAnother() {
		try (FileSystem fs = posixFileSystem()) {
			final Path file = write(fs, ALICE_PRIVATE + "\n", OWNER_ONLY);

			assertThat(SealingKeyFile.loadOrCreate(file), equalTo(HEX.parseHex(ALICE_PRIVATE)));
			assertThat(Files.readString(file), equalTo(ALICE_PRIVATE + "\n"));
		}
	}

	@Example
	@SneakyThrows
	public void makesADifferentKeyForEachFile() {
		try (FileSystem fs = posixFileSystem()) {
			final byte[] first = SealingKeyFile.loadOrCreate(fs.getPath("/one/" + SealingKeyFile.FILE));
			final byte[] second = SealingKeyFile.loadOrCreate(fs.getPath("/two/" + SealingKeyFile.FILE));

			assertThat(first, not(equalTo(second)));
		}
	}

	@Example
	@SneakyThrows
	public void createsTheKeyWhereThereAreNoPermissionsToRestrict() {
		try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
			final Path file = fs.getPath(KEY_FILE);
			final byte[] created = SealingKeyFile.loadOrCreate(file);

			assertThat(SealingKeyFile.loadOrCreate(file), equalTo(created));
		}
	}

	@Example
	public void derivesThePublicKeyOfX25519() {
		assertThat(SealingKeyFile.publicKey(HEX.parseHex(ALICE_PRIVATE)), equalTo(HEX.parseHex(ALICE_PUBLIC)));
	}

	@Example
	public void refusesAPrivateKeyOfTheWrongLengthWhenDerivingThePublicKey() {
		for (int length : new int[] { 0, 31, 33, 64 }) {
			assertThrows(IllegalArgumentException.class, () -> SealingKeyFile.publicKey(new byte[length]));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyThatOthersCanRead() {
		try (FileSystem fs = posixFileSystem()) {
			final Path file = write(fs, ALICE_PRIVATE + "\n", "rw-r--r--");
			final String message = messageOfLoading(file);

			assertThat(message, containsString(KEY_FILE));
			assertThat(message, containsString("chmod 600"));
			assertThat(message, not(containsString(ALICE_PRIVATE)));
		}
	}

	@Example
	@SneakyThrows
	public void refusesWhatIsNotOneLineOfHexadecimalCharactersWithoutRevealingIt() {
		final List<String> damaged = List.of(
			ALICE_PRIVATE.substring(1),
			ALICE_PRIVATE + "0",
			"g" + ALICE_PRIVATE.substring(1),
			"0x" + ALICE_PRIVATE.substring(2),
			ALICE_PRIVATE + "\n" + ALICE_PRIVATE,
			ALICE_PRIVATE + " trailing",
			"",
			"   "
		);

		try (FileSystem fs = posixFileSystem()) {
			for (String content : damaged) {
				final String message = messageOfLoading(write(fs, content + "\n", OWNER_ONLY));

				assertThat(message, containsString(KEY_FILE));
				assertThat(message, containsString(String.valueOf(2 * SealingKeyFile.KEY_SIZE)));
				assertThat(message, not(containsString(ALICE_PRIVATE.substring(2, 32))));
			}
		}
	}

	@Example
	@SneakyThrows
	public void refusesAFileFarLargerThanAKeyWithoutReadingItAll() {
		try (FileSystem fs = posixFileSystem()) {
			final Path file = write(fs, ALICE_PRIVATE + "\n" + " ".repeat(1 << 20), OWNER_ONLY);

			assertThat(messageOfLoading(file), containsString(KEY_FILE));
		}
	}

	@Example
	@SneakyThrows
	public void refusesAKeyFileThatIsNoRegularFile() {
		try (FileSystem fs = posixFileSystem()) {
			Files.createDirectories(fs.getPath(KEY_FILE));

			assertThat(messageOfLoading(fs.getPath(KEY_FILE)), containsString("regular file"));
		}
	}

	@Example
	@SneakyThrows
	public void neverReplacesALinkEvenWhenItPointsNowhere() {
		try (FileSystem fs = posixFileSystem()) {
			final Path link = fs.getPath(KEY_FILE);

			Files.createDirectories(link.getParent());
			Files.createSymbolicLink(link, fs.getPath("/elsewhere/missing.key"));

			assertThat(messageOfLoading(link), containsString(KEY_FILE));
			assertThat(Files.isSymbolicLink(link), equalTo(true));
			assertThat(Files.exists(fs.getPath("/elsewhere")), equalTo(false));
		}
	}

	/* A process that dies between writing the temporary file and linking it leaves a file with the key behind */
	@Example
	@SneakyThrows
	public void removesTheTemporaryFilesOfEarlierCreationsOlderThanAMinuteAndNothingElse() {
		try (FileSystem fs = posixFileSystem()) {
			final Path directory = fs.getPath(DATA_DIRECTORY);
			final FileTime old = FileTime.from(Instant.now().minus(Duration.ofMinutes(2)));
			final List<String> stale = List.of("gridnode-sealing1234567890123.tmp", "gridnode-sealing7.tmp");
			final List<String> kept = List.of("gridnode-sealing55.tmp", "gridnode-sealing-backup.tmp",
				"gridnode-sealing55.txt", "other.tmp", "gridnode-sealing.key.old");

			Files.createDirectories(directory);

			for (String name : Stream.concat(stale.stream(), kept.stream()).toList()) {
				Files.writeString(directory.resolve(name), "x");
				Files.setLastModifiedTime(directory.resolve(name), old);
			}

			Files.setLastModifiedTime(directory.resolve("gridnode-sealing55.tmp"), FileTime.from(Instant.now()));
			SealingKeyFile.loadOrCreate(fs.getPath(KEY_FILE));

			assertThat(namesIn(directory), containsInAnyOrder(
				Stream.concat(kept.stream(), Stream.of(SealingKeyFile.FILE)).toArray()));
		}
	}
}
