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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.model.util.OwnerOnlyFile;

/* A client seals a session to the public key that a gridnode announces, and the gridnode opens it with the private
   key of this file. The key is one line of hexadecimal characters, which messages about it never repeat, and the
   file stays in the data directory. */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SealingKeyFile {
	public static final String FILE = "gridnode-sealing.key";
	public static final int KEY_SIZE = X25519PrivateKeyParameters.KEY_SIZE;

	private static final int HEX_SIZE = KEY_SIZE * 2;
	/* A key, its line ending and room for stray blanks: a bigger file is not a key file */
	private static final int MAX_BYTES = HEX_SIZE * 2;
	private static final Pattern KEY = Pattern.compile("\\p{XDigit}{" + HEX_SIZE + "}");
	private static final String PARTIAL_PREFIX = "gridnode-sealing";
	/* What Files.createTempFile makes of the prefix and no suffix: the prefix, a number and .tmp */
	private static final Pattern PARTIAL = Pattern.compile(PARTIAL_PREFIX + "[0-9]+\\.tmp");
	private static final Duration STALE_AFTER = Duration.ofMinutes(1);

	/* The key is made on the first start of the gridnode and kept from then on, as announcing another one would
	   leave every session that was sealed to the earlier one unreadable */
	public static byte[] loadOrCreate(Path file) {
		if (!Files.exists(file)) {
			try {
				create(file);
			} catch (FileAlreadyExistsException ex) {
				log.atDebug().addArgument(file).log("Another creator made the sealing key file {}");
			} catch (IOException ex) {
				throw new IllegalArgumentException("Cannot create the sealing key file " + file + ": " + ex,
					ex);
			}
		}

		return load(file);
	}

	public static byte[] publicKey(byte[] privateKey) {
		if (privateKey.length != KEY_SIZE) {
			throw new IllegalArgumentException("An X25519 private key is exactly " + KEY_SIZE + " bytes, not "
				+ privateKey.length);
		}

		return new X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().getEncoded();
	}

	private static byte[] load(Path file) {
		final String line = read(file).strip();

		if (!KEY.matcher(line).matches()) {
			throw malformed(file);
		}

		return HexFormat.of().parseHex(line);
	}

	private static void create(Path file) throws IOException {
		final Path directory = Files.createDirectories(file.toAbsolutePath().getParent());

		removeStalePartialFiles(directory);

		final Path partial = Files.createTempFile(directory, PARTIAL_PREFIX, null,
			OwnerOnlyFile.attributesIn(directory)
		);
		final byte[] key = new X25519PrivateKeyParameters(new SecureRandom()).getEncoded();

		try {
			Files.writeString(partial, HexFormat.of().formatHex(key) + "\n", StandardCharsets.UTF_8);
			publish(partial, file);
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	/* A process that dies between writing the partial file, which holds a key, and linking it leaves the file behind.
	   Only the files that this class names and that are old enough not to belong to a creation in progress are
	   removed. A directory that cannot be listed leaves them, which does no harm: only the owner can read them. */
	private static void removeStalePartialFiles(Path directory) {
		final FileTime cutoff = FileTime.from(Instant.now().minus(STALE_AFTER));

		try (Stream<Path> entries = Files.list(directory)) {
			entries.filter(entry -> PARTIAL.matcher(entry.getFileName().toString()).matches())
				.filter(entry -> isStale(entry, cutoff)).forEach(SealingKeyFile::deleteQuietly);
		} catch (IOException ex) {
			log.atDebug().addArgument(directory).log("Could not look for old partial key files in {}");
		}
	}

	private static boolean isStale(Path entry, FileTime cutoff) {
		try {
			return Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
				&& Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) < 0;
		} catch (IOException ex) {
			return false;
		}
	}

	private static void deleteQuietly(Path entry) {
		try {
			Files.deleteIfExists(entry);
		} catch (IOException ex) {
			log.atDebug().addArgument(entry).log("Could not remove the old partial key file {}");
		}
	}

	/* A hard link shows up complete and fails if the path is taken, so a key is never replaced, not even by a
	   racing creator. Only a file system without hard links gets a plain move, which refuses an existing target
	   as well but checks it in a step of its own. */
	private static void publish(Path partial, Path file) throws IOException {
		try {
			Files.createLink(file, partial);
		} catch (FileAlreadyExistsException ex) {
			throw ex;
		} catch (UnsupportedOperationException | FileSystemException ex) {
			Files.move(partial, file);
		}
	}

	/* A file that is there and is no regular file, a pipe or a device, could be read for ever; one that is not there
	   is left to the read, which says so */
	private static String read(Path file) {
		try {
			if (Files.exists(file) && !Files.isRegularFile(file)) {
				throw new IllegalArgumentException(file + " must be a regular file that holds the key");
			}

			OwnerOnlyFile.require(file);
			return readBounded(file);
		} catch (IOException ex) {
			throw new IllegalArgumentException("Cannot read the sealing key file " + file + ": " + ex, ex);
		}
	}

	/* The size a file claims cannot be trusted (a device says 0), so what is read is bounded instead */
	private static String readBounded(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			final byte[] bytes = in.readNBytes(MAX_BYTES + 1);

			if (bytes.length > MAX_BYTES) {
				throw malformed(file);
			}

			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	private static IllegalArgumentException malformed(Path file) {
		return new IllegalArgumentException(file + " must hold the key as one line of " + HEX_SIZE
			+ " hexadecimal characters");
	}
}
