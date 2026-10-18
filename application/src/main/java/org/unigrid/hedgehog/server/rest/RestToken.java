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

package org.unigrid.hedgehog.server.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RestToken {
	public static final String FILE = "rest.token";
	private static final int TOKEN_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	public static Path getFile() {
		return ApplicationDirectory.create().getUserDataDir().resolve(FILE);
	}

	public static String generate() {
		final byte[] token = new byte[TOKEN_BYTES];

		RANDOM.nextBytes(token);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
	}

	public static void write(Path file, String token) throws IOException {
		final Path directory = file.toAbsolutePath().getParent();

		Files.createDirectories(directory);

		final Path partial = Files.createTempFile(directory, FILE, null, ownerOnly(directory));

		Files.writeString(partial, token);
		Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/* Created restricted rather than restricted afterwards, so the token is never readable by others */
	private static FileAttribute<?>[] ownerOnly(Path directory) throws IOException {
		if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
			return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString("rw-------"))
			};
		}

		return new FileAttribute<?>[0];
	}

	public static String read(Path file) throws IOException {
		return Files.readString(file).trim();
	}

	public static Optional<String> getConfigured() {
		return Optional.ofNullable(RestOptions.getToken()).filter(Predicate.not(String::isBlank));
	}

	public static String resolve() throws IOException {
		final Optional<String> configured = getConfigured();
		return configured.isPresent() ? configured.get() : read(getFile());
	}
}
