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

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.IOUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Unzipper {
    private static final int PROGRESS_WIDTH = 20;
    private static final int PROGRESS_CLEARANCE = 15;

    public static void unzip(SeekableByteChannel in, Path destination) throws IOException {
        try (ZipFile archive = new ZipFile(in)) {
            archive.getEntries().asIterator().forEachRemaining(entry -> {
                try {
                    final Path target = destination.resolve(entry.getName());
                    if (!target.normalize().startsWith(destination)) {
                        throw new IOException("Zip slip detected: " + entry.getName());
                    }
                    final Path path = target.normalize();

                    if (entry.isDirectory()) {
                        Files.createDirectories(path);
                    } else {
                        Files.createDirectories(path.getParent());
                        final File file = path.toFile();
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
                            IOUtils.copy(archive.getInputStream(entry), out);
                        }
                        
                        // Sätt körrättighet om det är i bin eller ett skript
                        if (path.getParent().endsWith(NativeProperties.BIN_DIRECTORY) || 
                            path.getFileName().toString().endsWith(".sh")) {
                            file.setExecutable(true, false);
                        }
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}

