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
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.model.util.HexKeyFile;

/* The seed of a validator is one line of hexadecimal characters, which messages about it never repeat */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerKeyFile {
	private static final HexKeyFile SEED_FILE = new HexKeyFile("ledger key file", "seed", "ledger-key",
		Ed25519.SEED_SIZE, LedgerKeyFile::newSeed);

	public static byte[] load(Path file) {
		return SEED_FILE.load(file);
	}

	public static void create(Path file) throws IOException {
		SEED_FILE.create(file);
	}

	public static String publicKeyHex(byte[] seed) {
		return HexFormat.of().formatHex(Ed25519.publicKey(seed).bytes());
	}

	private static byte[] newSeed() {
		final byte[] seed = new byte[Ed25519.SEED_SIZE];

		new SecureRandom().nextBytes(seed);
		return seed;
	}
}
