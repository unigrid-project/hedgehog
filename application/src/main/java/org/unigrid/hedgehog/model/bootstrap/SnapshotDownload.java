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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.ReleaseKey;

/*
   The snapshot is written to an unpredictable name beside its destination, so the final move stays
   on one filesystem and is therefore atomic and no other local process can substitute the file
   between the moment it verifies and the moment it lands. It is only moved into place once the
   transfer matches the hash published beside it under the release key, and it opens as a snapshot
   this build understands whose signature verifies against a trusted network key. A download that
   cannot be verified never replaces a snapshot that could be.

   The size ceiling matters because authenticity is established only after the bytes are on disk.
   Without it a small compressed body that expands without end fills the disk before anything is
   checked.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotDownload {
	public static final String COMPRESSED_SUFFIX = ".gz";
	public static final long MAXIMUM_SIZE = 2L << 30;
	public static final String HASH_SUFFIX = ".sha256";
	public static final String HASH_SIGNATURE_SUFFIX = HASH_SUFFIX + ".asc";

	private static final String HASH_ALGORITHM = "SHA-256";
	private static final Pattern HASH_LINE = Pattern.compile("([0-9a-f]{64}) [ *](.+)");
	private static final int SMALL_LIMIT = 4096;

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
		final byte[] publishedHash = publishedHash(source);

		Files.createDirectories(target.toAbsolutePath().getParent());

		final Path partial = Files.createTempFile(target.toAbsolutePath().getParent(),
			PARTIAL_PREFIX, PARTIAL_SUFFIX);

		try {
			if (!MessageDigest.isEqual(copy(source, partial, maximumSize, progress), publishedHash)) {
				throw new IOException("The download from " + source + " does not match the hash"
					+ " published with it and was not installed");
			}

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

	/*
	   The hash vouches for the file as published, so it is checked before anything the size of the
	   snapshot is transferred, and it must name the very file it is published beside: a hash of
	   another release's snapshot, signed or not, proves nothing about this one.
	*/
	private static byte[] publishedHash(URL source) throws IOException {
		final byte[] hash = fetchSmall(sibling(source, HASH_SUFFIX));

		if (!ReleaseKey.verify(hash, fetchSmall(sibling(source, HASH_SIGNATURE_SUFFIX)))) {
			throw new IOException("The hash published with " + source + " carries no valid signature"
				+ " from the release key");
		}

		final Matcher line = HASH_LINE.matcher(new String(hash, StandardCharsets.US_ASCII).strip());
		final String name = source.getPath().substring(source.getPath().lastIndexOf('/') + 1);

		if (!line.matches() || !line.group(2).equals(name)) {
			throw new IOException("The hash published with " + source + " is not a hash of " + name);
		}

		return HexFormat.of().parseHex(line.group(1));
	}

	private static URL sibling(URL source, String suffix) {
		try {
			return URI.create(source + suffix).toURL();
		} catch (MalformedURLException ex) {
			throw new IllegalArgumentException(ex);
		}
	}

	private static byte[] fetchSmall(URL url) throws IOException {
		try {
			@Cleanup final InputStream stream = connect(url).getInputStream();
			final byte[] contents = stream.readNBytes(SMALL_LIMIT + 1);

			if (contents.length > SMALL_LIMIT) {
				throw new IOException("it is larger than " + SMALL_LIMIT + " bytes");
			}

			return contents;

		} catch (IOException ex) {
			throw new IOException("Could not fetch " + url + ": " + ex.getMessage(), ex);
		}
	}

	/*
	   The hash covers the file as transferred, so the digest sits beneath the decompression and the
	   transfer is read to its end, including anything the decompressor leaves behind.
	*/
	private static byte[] copy(URL source, Path partial, long maximumSize, IntConsumer progress) throws IOException {
		final DigestInputStream transfer = new DigestInputStream(open(source, progress), sha256());
		@Cleanup final InputStream stream = source.getPath().endsWith(COMPRESSED_SUFFIX)
			? new GZIPInputStream(transfer) : transfer;
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

		transfer.transferTo(OutputStream.nullOutputStream());
		return transfer.getMessageDigest().digest();
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance(HASH_ALGORITHM);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
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
		final URLConnection connection = connect(source);
		final InputStream transfer = connection.getInputStream();
		final long length = connection.getContentLengthLong();

		return length > 0 ? new ProgressStream(transfer, length, progress) : transfer;
	}

	private static URLConnection connect(URL source) throws IOException {
		final URLConnection connection = source.openConnection();

		connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
		connection.setReadTimeout(READ_TIMEOUT_MILLIS);
		return connection;
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
