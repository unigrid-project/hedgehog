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

package org.unigrid.hedgehog.model.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/* A file that holds a secret as one line of hexadecimal characters, which messages about it never repeat. The file
   is readable by its owner only and is never replaced once it is there. The name of the file and of the secret it
   holds go into the messages. */
@Slf4j
public final class HexKeyFile {
	/* What Files.createTempFile makes of the prefix and no suffix: the prefix, a number and .tmp */
	private static final String PARTIAL_SUFFIX = "[0-9]+\\.tmp";
	private static final Duration STALE_AFTER = Duration.ofMinutes(1);

	private final String name;
	private final String secret;
	private final String partialPrefix;
	private final Supplier<byte[]> generator;
	private final int hexSize;
	/* A secret, its line ending and room for stray blanks: a bigger file is not a key file */
	private final int maxBytes;
	private final Pattern pattern;
	private final Pattern partial;

	/* The generator makes a new secret of the size the file holds each time it is asked */
	public HexKeyFile(String name, String secret, String partialPrefix, int size, Supplier<byte[]> generator) {
		this.name = name;
		this.secret = secret;
		this.partialPrefix = partialPrefix;
		this.generator = generator;
		this.hexSize = size * 2;
		this.maxBytes = hexSize * 2;
		this.pattern = Pattern.compile("\\p{XDigit}{" + hexSize + "}");
		this.partial = Pattern.compile(Pattern.quote(partialPrefix) + PARTIAL_SUFFIX);
	}

	public byte[] load(Path file) {
		final String line = read(file).strip();

		if (!pattern.matcher(line).matches()) {
			throw malformed(file);
		}

		return HexFormat.of().parseHex(line);
	}

	public void create(Path file) throws IOException {
		final Path directory = Files.createDirectories(file.toAbsolutePath().getParent());

		removeStalePartialFiles(directory);

		final Path partialFile = Files.createTempFile(directory, partialPrefix, null,
			OwnerOnlyFile.attributesIn(directory)
		);

		try {
			Files.writeString(partialFile, HexFormat.of().formatHex(generator.get()) + "\n",
				StandardCharsets.UTF_8);
			publish(partialFile, file);
		} finally {
			Files.deleteIfExists(partialFile);
		}
	}

	/* A process that dies between writing the partial file, which holds a secret, and linking it leaves the file
	   behind. Only the files that this key file names and that are old enough not to belong to a creation in
	   progress are removed. A directory that cannot be listed leaves them, which does no harm: only the owner can
	   read them. */
	private void removeStalePartialFiles(Path directory) {
		final FileTime cutoff = FileTime.from(Instant.now().minus(STALE_AFTER));

		try (Stream<Path> entries = Files.list(directory)) {
			entries.filter(entry -> partial.matcher(entry.getFileName().toString()).matches())
				.filter(entry -> isStale(entry, cutoff)).forEach(HexKeyFile::deleteQuietly);
		} catch (IOException ex) {
			log.atDebug().addArgument(directory).log("Could not look for old partial key files in {}");
		}
	}

	private static boolean isStale(Path entry, FileTime cutoff) {
		try {
			return Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
				&& Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) < 0;
		} catch (IOException ex) {
			return false;
		}
	}

	private static void deleteQuietly(Path entry) {
		try {
			Files.deleteIfExists(entry);
		} catch (IOException ex) {
			log.atDebug().addArgument(entry).log("Could not remove the old partial key file {}");
		}
	}

	/* A hard link shows up complete and fails if the path is taken, so a key is never replaced, not even by a
	   racing creator. Only a file system without hard links gets a plain move, which refuses an existing target
	   as well but checks it in a step of its own. */
	private static void publish(Path partialFile, Path file) throws IOException {
		try {
			Files.createLink(file, partialFile);
		} catch (FileAlreadyExistsException ex) {
			throw ex;
		} catch (UnsupportedOperationException | FileSystemException ex) {
			Files.move(partialFile, file);
		}
	}

	/* A file that is there and is no regular file, a pipe or a device, could be read for ever; one that is not
	   there is left to the read, which says so */
	private String read(Path file) {
		try {
			if (Files.exists(file) && !Files.isRegularFile(file)) {
				throw new IllegalArgumentException(file + " must be a regular file that holds the "
					+ secret);
			}

			OwnerOnlyFile.require(file);
			return readBounded(file);
		} catch (IOException ex) {
			throw new IllegalArgumentException("Cannot read the " + name + " " + file + ": " + ex, ex);
		}
	}

	/* The size a file claims cannot be trusted (a device says 0), so what is read is bounded instead */
	private String readBounded(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			final byte[] bytes = in.readNBytes(maxBytes + 1);

			if (bytes.length > maxBytes) {
				throw malformed(file);
			}

			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	private IllegalArgumentException malformed(Path file) {
		return new IllegalArgumentException(file + " must hold the " + secret + " as one line of " + hexSize
			+ " hexadecimal characters");
	}
}
