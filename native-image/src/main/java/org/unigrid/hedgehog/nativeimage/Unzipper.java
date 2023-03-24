/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.nativeimage;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.exec.OS;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class Unzipper {
	private static final int PROGRESS_WIDTH = 20;
	private static final int PROGRESS_CLEARANCE = 15;

	private static void printProgress(long position, long size) {
		final int complete = (int) ((float) (position < 0 ? 0 : position) / size * PROGRESS_WIDTH);
		char incompleteCharacter;
		char completeCharacter;

		if (OS.isFamilyWindows()) {
			incompleteCharacter = ' ';
			completeCharacter = '#';
		} else {
			incompleteCharacter = '░'; // U+2591
			completeCharacter = '█'; // U+2588
		}

		System.out.print(String.format("\r Unpacking: [%s%s]%s\r",
			StringUtils.repeat(completeCharacter, complete),
			StringUtils.repeat(incompleteCharacter, PROGRESS_WIDTH - complete),
			StringUtils.repeat(" ", PROGRESS_CLEARANCE)
		));
	}

	private static Path normalize(Path destination, ZipArchiveEntry entry) throws IOException {
		final Path target = destination.resolve(Path.of(NativeProperties.getHash(), entry.getName()));

		/* Verify normalized name target to avoid zip slip vulnerability */
		if (!target.normalize().startsWith(target)) {
			throw new IOException("Zip slip detected at: " + entry.getName());
		}

		return target.normalize();
	}

	public static void unzip(SeekableByteChannel in, Path destination) throws IOException {
		try (ZipFile archive = new ZipFile(in)) {
			archive.getEntries().asIterator().forEachRemaining(entry -> {
				try {
					final Path path = normalize(destination, entry);

					if (entry.isDirectory()) {
						Files.createDirectories(path);
					} else {
						printProgress(in.position(), in.size());
						Files.createDirectories(path.getParent());

						final File file = path.toFile();
						final OutputStream out = new BufferedOutputStream(
							new FileOutputStream(file)
						);

						IOUtils.copy(archive.getInputStream(entry), out);
						out.flush();
						IOUtils.closeQuietly(out);

						if (path.getParent().endsWith(NativeProperties.BIN_DIRECTORY)) {
							file.setExecutable(true);
						}
					}
				} catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			});

			/* Just clear the line */
			System.out.print(String.format("\r%s\r",
				StringUtils.repeat(" ", PROGRESS_WIDTH + PROGRESS_CLEARANCE))
			);
		}
	}
}
