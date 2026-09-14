/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
   The snapshot lands beside its destination so the final move stays on one filesystem and is
   therefore atomic, and it is only moved into place once its signature verifies. A download
   that cannot be verified never replaces a snapshot that could be.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotDownload {
	public static final String COMPRESSED_SUFFIX = ".gz";

	private static final String PARTIAL_SUFFIX = ".part";
	private static final long PROGRESS_INTERVAL = 32L << 20;
	private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
	private static final int READ_TIMEOUT_MILLIS = 120_000;

	public static void install(URL source, Path target) throws IOException {
		final Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);

		Files.createDirectories(target.toAbsolutePath().getParent());

		try {
			copy(source, partial);
			verify(partial, source);
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
			log.atInfo().log("Installed the legacy chain snapshot at {}", target);

		} finally {
			Files.deleteIfExists(partial);
		}
	}

	private static void verify(Path partial, URL source) throws IOException {
		final SignatureStatus status = SnapshotSignature.read(partial).getStatus();

		if (status != SignatureStatus.SIGNED) {
			throw new IOException("The snapshot at " + source + " is " + status
				+ " and was not installed");
		}
	}

	private static void copy(URL source, Path partial) throws IOException {
		@Cleanup final InputStream stream = open(source);
		@Cleanup final OutputStream out = Files.newOutputStream(partial);
		final byte[] buffer = new byte[1 << 16];
		long total = 0;
		long reported = 0;

		for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
			out.write(buffer, 0, read);
			total += read;

			if (total - reported >= PROGRESS_INTERVAL) {
				reported = total;
				log.atInfo().log("Downloaded {} MiB", total >> 20);
			}
		}
	}

	private static InputStream open(URL source) throws IOException {
		final URLConnection connection = source.openConnection();

		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);

		final InputStream stream = connection.getInputStream();

		return source.getPath().endsWith(COMPRESSED_SUFFIX) ? new GZIPInputStream(stream) : stream;
	}
}
