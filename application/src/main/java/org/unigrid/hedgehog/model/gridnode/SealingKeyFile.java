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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.unigrid.hedgehog.model.util.HexKeyFile;

/* A client seals a session to the public key that a gridnode announces, and the gridnode opens it with the private
   key of this file. The key is one line of hexadecimal characters, which messages about it never repeat, and the
   file stays in the data directory. */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SealingKeyFile {
	public static final String FILE = "gridnode-sealing.key";
	public static final int KEY_SIZE = X25519PrivateKeyParameters.KEY_SIZE;

	private static final HexKeyFile KEY_FILE = new HexKeyFile("sealing key file", "key", "gridnode-sealing",
		KEY_SIZE, () -> new X25519PrivateKeyParameters(new SecureRandom()).getEncoded());

	/* The key is made on the first start of the gridnode and kept from then on, as announcing another one would
	   leave every session that was sealed to the earlier one unreadable */
	public static byte[] loadOrCreate(Path file) {
		if (!Files.exists(file)) {
			try {
				KEY_FILE.create(file);
			} catch (FileAlreadyExistsException ex) {
				log.atDebug().addArgument(file).log("Another creator made the sealing key file {}");
			} catch (IOException ex) {
				throw new IllegalArgumentException("Cannot create the sealing key file " + file + ": " + ex,
					ex);
			}
		}

		return KEY_FILE.load(file);
	}

	public static byte[] publicKey(byte[] privateKey) {
		if (privateKey.length != KEY_SIZE) {
			throw new IllegalArgumentException("An X25519 private key is exactly " + KEY_SIZE + " bytes, not "
				+ privateKey.length);
		}

		return new X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().getEncoded();
	}
}
