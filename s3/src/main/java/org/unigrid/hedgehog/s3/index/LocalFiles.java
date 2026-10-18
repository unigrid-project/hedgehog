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

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/* The local files hold blob ids, and an id grants read access to its blob, so only their owner may read them */
final class LocalFiles {
	private static final FileAttribute<?>[] OWNER_ONLY = {
		PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
	};

	private LocalFiles() {
		/* Static helpers only */
	}

	static FileAttribute<?>[] ownerOnly(final Path path) {
		return path.getFileSystem().supportedFileAttributeViews().contains("posix") ? OWNER_ONLY
			: new FileAttribute<?>[0];
	}

	static FileChannel open(final Path path, final OpenOption... options) throws IOException {
		return FileChannel.open(path, Set.of(options), ownerOnly(path));
	}

	static void writeFully(final FileChannel channel, final ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
	}

	/* A rename or a new file only survives a crash once the directory holding it is synced too */
	static void sync(final Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException exception) {
			/* Not every platform opens directories, and there a rename is as durable as the platform makes it */
		}
	}

	static void replace(final Path path, final byte[] content) throws IOException {
		final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

		Files.deleteIfExists(temporary);

		try (FileChannel channel = open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			writeFully(channel, ByteBuffer.wrap(content));
			channel.force(true);
		}

		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		sync(path.getParent());
	}
}
