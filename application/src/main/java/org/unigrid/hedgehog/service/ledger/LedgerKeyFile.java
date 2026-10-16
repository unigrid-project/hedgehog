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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
	private static final Pattern SEED = Pattern.compile("\\p{XDigit}{" + HEX_SIZE + "}");
	private static final String PARTIAL_PREFIX = "ledger-key";

	public static byte[] load(Path file) {
		final String line = read(file).strip();

		if (!SEED.matcher(line).matches()) {
			throw new IllegalArgumentException(file + " must hold the seed as one line of " + HEX_SIZE
				+ " hexadecimal characters");
		}

		return HexFormat.of().parseHex(line);
	}

	/* A move over an existing file would silently replace a key, so a path that is taken is refused first */
	public static void create(Path file) throws IOException {
		if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
			throw new FileAlreadyExistsException(file.toString());
		}

		final Path directory = Files.createDirectories(file.toAbsolutePath().getParent());
		final Path partial = Files.createTempFile(directory, PARTIAL_PREFIX, null,
			OwnerOnlyFile.attributesIn(directory)
		);
		final byte[] seed = new byte[Ed25519.SEED_SIZE];

		new SecureRandom().nextBytes(seed);

		try {
			Files.writeString(partial, HexFormat.of().formatHex(seed) + "\n", StandardCharsets.UTF_8);
			Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	public static String publicKeyHex(byte[] seed) {
		return HexFormat.of().formatHex(Ed25519.publicKey(seed).bytes());
	}

	private static String read(Path file) {
		try {
			OwnerOnlyFile.require(file);
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new IllegalArgumentException("Cannot read the ledger key file " + file + ": " + ex, ex);
		}
	}
}
