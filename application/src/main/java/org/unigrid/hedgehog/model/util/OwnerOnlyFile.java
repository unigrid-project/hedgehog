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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/* On a file system without POSIX permissions there is nothing to check and nothing to restrict */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OwnerOnlyFile {
	private static final String OWNER_ONLY = "rw-------";

	public static void require(Path file) throws IOException {
		final PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);

		if (view != null && view.readAttributes().permissions().stream().anyMatch(OwnerOnlyFile::isGroupOrOther)) {
			throw new IllegalArgumentException(file + " must be readable by its owner only, try chmod 600");
		}
	}

	/* Created restricted rather than restricted afterwards, so the content is never readable by others */
	public static FileAttribute<?>[] attributesIn(Path directory) throws IOException {
		if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
			return new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(
				PosixFilePermissions.fromString(OWNER_ONLY))
			};
		}

		return new FileAttribute<?>[0];
	}

	private static boolean isGroupOrOther(PosixFilePermission permission) {
		return permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_");
	}
}
