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

package org.unigrid.hedgehog.model.bootstrap;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.IntConsumer;
import java.util.zip.GZIPInputStream;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/*
   The snapshot is written to an unpredictable name beside its destination, so the final move stays
   on one filesystem and is therefore atomic and no other local process can substitute the file
   between the moment it verifies and the moment it lands. It is only moved into place once it
   opens as a snapshot this build understands and its signature verifies against a trusted key. A
   download that cannot be verified never replaces a snapshot that could be.

   The size ceiling matters because authenticity is established only after the bytes are on disk.
   Without it a small compressed body that expands without end fills the disk before anything is
   checked.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotDownload {
	public static final String COMPRESSED_SUFFIX = ".gz";
	public static final long MAXIMUM_SIZE = 2L << 30;

	private static final String PARTIAL_PREFIX = "snapshot-";
	private static final String PARTIAL_SUFFIX = ".part";
	private static final long PROGRESS_INTERVAL = 32L << 20;
	private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
	private static final int READ_TIMEOUT_MILLIS = 120_000;

	public static void install(URL source, Path target) throws IOException {
		install(source, target, percent -> { });
	}

	/* The progress hears each new percentage of the transfer, and nothing when its size is unknown. */
	public static void install(URL source, Path target, IntConsumer progress) throws IOException {
		install(source, target, MAXIMUM_SIZE, progress);
	}

	/* The ceiling is a parameter so a test can prove the guard fires without moving two gibibytes. */
	static void install(URL source, Path target, long maximumSize, IntConsumer progress) throws IOException {
		Files.createDirectories(target.toAbsolutePath().getParent());

		final Path partial = Files.createTempFile(target.toAbsolutePath().getParent(),
			PARTIAL_PREFIX, PARTIAL_SUFFIX);

		try {
			copy(source, partial, maximumSize, progress);
			verify(partial, source);
			Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
			log.atInfo().log("Installed the legacy chain snapshot at {}", target);

		} finally {
			discard(partial);
		}
	}

	/*
	   The inspector checks the magic, the format version and the signature, so a snapshot this build
	   cannot read never installs. It deliberately does not memory-map: the very next statement renames
	   this file, which a mapping would block on Windows.
	*/
	private static void verify(Path partial, URL source) throws IOException {
		final SignatureStatus status = SnapshotInspector.validate(partial);

		if (status != SignatureStatus.SIGNED) {
			throw new IOException("The snapshot at " + source + " carries no signature from a"
				+ " trusted key and was not installed");
		}
	}

	private static void copy(URL source, Path partial, long maximumSize, IntConsumer progress) throws IOException {
		@Cleanup final InputStream stream = open(source, progress);
		@Cleanup final OutputStream out = Files.newOutputStream(partial);
		final byte[] buffer = new byte[1 << 16];
		long total = 0;
		long reported = 0;

		for (int read = stream.read(buffer); read > 0; read = stream.read(buffer)) {
			total += read;

			if (total > maximumSize) {
				throw new IOException("The download from " + source + " passed " + maximumSize
					+ " bytes and was abandoned");
			}

			out.write(buffer, 0, read);

			if (total - reported >= PROGRESS_INTERVAL) {
				reported = total;
				log.atInfo().log("Downloaded {} MiB", total >> 20);
			}
		}
	}

	/* Cleanup must never replace the failure that caused it with one about the leftover file. */
	private static void discard(Path partial) {
		try {
			Files.deleteIfExists(partial);
		} catch (IOException ex) {
			log.atWarn().log("Could not remove the partial download at {}: {}", partial,
				ex.getMessage());
		}
	}

	/* Progress is counted before decompression, since only the transferred size is known up front. */
	private static InputStream open(URL source, IntConsumer progress) throws IOException {
		final URLConnection connection = source.openConnection();

		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);

		final InputStream transfer = connection.getInputStream();
		final long length = connection.getContentLengthLong();
		final InputStream stream = length > 0 ? new ProgressStream(transfer, length, progress) : transfer;

		return source.getPath().endsWith(COMPRESSED_SUFFIX) ? new GZIPInputStream(stream) : stream;
	}

	private static final class ProgressStream extends FilterInputStream {
		private static final int WHOLE = 100;

		private final long length;
		private final IntConsumer progress;
		private long received;
		private int reported = -1;

		ProgressStream(InputStream in, long length, IntConsumer progress) {
			super(in);
			this.length = length;
			this.progress = progress;
		}

		@Override
		public int read() throws IOException {
			final int value = super.read();

			count(value < 0 ? 0 : 1);
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int count) throws IOException {
			final int read = super.read(buffer, offset, count);

			count(Math.max(read, 0));
			return read;
		}

		private void count(int read) {
			received += read;

			final int percent = (int) Math.min(received * WHOLE / length, WHOLE);

			if (percent != reported) {
				reported = percent;
				progress.accept(percent);
			}
		}
	}
}
