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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HexFormat;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.ValidatorSet;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerKeyFixtures {
	public static final String OWNER_ONLY = "rw-------";
	public static final String KEY_FILE = "/keys/ledger.key";

	/* The plain unix configuration has no permission attributes, which the key file check reads */
	public static FileSystem posixFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix().toBuilder()
			.setAttributeViews("basic", "owner", "posix", "unix").build());
	}

	public static FileSystem plainFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix());
	}

	@SneakyThrows
	public static Path write(FileSystem fs, String name, String content, String permissions) {
		final Path file = fs.getPath(name);

		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);

		if (permissions != null) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
		}

		return file;
	}

	public static Path writeSeed(FileSystem fs, byte[] seed) {
		return write(fs, KEY_FILE, HexFormat.of().formatHex(seed) + "\n", OWNER_ONLY);
	}

	/* A genesis of the validators of the seeds, with no snapshot behind it */
	@SneakyThrows
	public static Path writeGenesis(FileSystem fs, String name, byte[]... validatorSeeds) {
		final Path file = fs.getPath(name);

		Files.createDirectories(file.getParent());
		GenesisFile.write(file, new LedgerGenesis(1_000, 100,
			new ValidatorSet(Stream.of(validatorSeeds).map(Ed25519::publicKey).toList())));
		return file;
	}
}
