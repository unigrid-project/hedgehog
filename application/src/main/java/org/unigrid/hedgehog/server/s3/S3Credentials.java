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

package org.unigrid.hedgehog.server.s3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.s3.spi.CredentialStore;

/*
   The access keys of the S3 endpoint, one '<access key id> <secret key>' per line of a file only its owner can
   read. It is read again for every request, so keys created or deleted while the daemon runs apply at once.
*/
@RequiredArgsConstructor
public class S3Credentials implements CredentialStore {
	public static final String DIRECTORY = "s3";
	public static final String FILE = "credentials";

	private static final String ACCESS_KEY_PREFIX = "HK";
	private static final String ACCESS_KEY_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
	private static final int ACCESS_KEY_LENGTH = 20;
	private static final int SECRET_KEY_BYTES = 30;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Path file;

	public record Key(String accessKeyId, String secretKey) {
	}

	public static Path getFile() {
		return ApplicationDirectory.create().getUserDataDir().resolve(DIRECTORY).resolve(FILE);
	}

	private static Key generate() {
		final StringBuilder accessKeyId = new StringBuilder(ACCESS_KEY_PREFIX);
		final byte[] secretKey = new byte[SECRET_KEY_BYTES];

		while (accessKeyId.length() < ACCESS_KEY_LENGTH) {
			accessKeyId.append(ACCESS_KEY_CHARACTERS.charAt(RANDOM.nextInt(ACCESS_KEY_CHARACTERS.length())));
		}

		RANDOM.nextBytes(secretKey);
		return new Key(accessKeyId.toString(), Base64.getEncoder().encodeToString(secretKey));
	}

	@Override
	public Optional<String> secretKey(final String accessKeyId) {
		try {
			return Optional.ofNullable(read().get(accessKeyId));
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public Key create() throws IOException {
		final Map<String, String> keys = read();
		final Key key = generate();

		keys.put(key.accessKeyId(), key.secretKey());
		write(keys);
		return key;
	}

	public List<String> list() throws IOException {
		return List.copyOf(read().keySet());
	}

	public boolean delete(final String accessKeyId) throws IOException {
		final Map<String, String> keys = read();
		final boolean deleted = keys.remove(accessKeyId) != null;

		if (deleted) {
			write(keys);
		}

		return deleted;
	}

	private Map<String, String> read() throws IOException {
		if (Files.notExists(file)) {
			return new TreeMap<>();
		}

		try (var lines = Files.lines(file)) {
			return lines.map(String::strip).filter(line -> !line.isEmpty()).map(line -> line.split("\\s+", 2))
				.filter(key -> key.length == 2).collect(Collectors.toMap(key -> key[0], key -> key[1],
					(first, second) -> second, TreeMap::new
				));
		}
	}

	/* Written whole beside the old file and moved over it, so a reader never sees half of it */
	private void write(final Map<String, String> keys) throws IOException {
		final Path directory = file.toAbsolutePath().getParent();

		Files.createDirectories(directory, ownerOnly("rwx------"));

		final Path partial = Files.createTempFile(directory, FILE, null, ownerOnly("rw-------"));

		Files.write(partial, keys.entrySet().stream().map(key -> key.getKey() + " " + key.getValue()).toList());
		Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	/* Created restricted rather than restricted afterwards, so no secret is ever readable by others */
	private FileAttribute<?>[] ownerOnly(final String permissions) {
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			return new FileAttribute<?>[] {
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
			};
		}

		return new FileAttribute<?>[0];
	}
}
