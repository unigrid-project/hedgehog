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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.model.util.OwnerOnlyFile;

/* The seed of a validator is one line of hexadecimal characters, which messages about it never repeat */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerKeyFile {
	private static final int HEX_SIZE = Ed25519.SEED_SIZE * 2;
	/* A seed, its line ending and room for stray blanks: a bigger file is not a key file */
	private static final int MAX_BYTES = HEX_SIZE * 2;
	private static final Pattern SEED = Pattern.compile("\\p{XDigit}{" + HEX_SIZE + "}");
	private static final String PARTIAL_PREFIX = "ledger-key";

	public static byte[] load(Path file) {
		final String line = read(file).strip();

		if (!SEED.matcher(line).matches()) {
			throw malformed(file);
		}

		return HexFormat.of().parseHex(line);
	}

	public static void create(Path file) throws IOException {
		final Path directory = Files.createDirectories(file.toAbsolutePath().getParent());
		final Path partial = Files.createTempFile(directory, PARTIAL_PREFIX, null,
			OwnerOnlyFile.attributesIn(directory)
		);
		final byte[] seed = new byte[Ed25519.SEED_SIZE];

		new SecureRandom().nextBytes(seed);

		try {
			Files.writeString(partial, HexFormat.of().formatHex(seed) + "\n", StandardCharsets.UTF_8);
			publish(partial, file);
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	public static String publicKeyHex(byte[] seed) {
		return HexFormat.of().formatHex(Ed25519.publicKey(seed).bytes());
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

	private static String read(Path file) {
		try {
			OwnerOnlyFile.require(file);
			return readBounded(file);
		} catch (IOException ex) {
			throw new IllegalArgumentException("Cannot read the ledger key file " + file + ": " + ex, ex);
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
		return new IllegalArgumentException(file + " must hold the seed as one line of " + HEX_SIZE
			+ " hexadecimal characters");
	}
}
