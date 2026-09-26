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

package org.unigrid.hedgehog.service;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/* Bucket names and object keys come straight from requests, so every path built from them is confined here */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StoragePath {
	public static Path bucket(Path dataDir, String bucket) {
		final Path base = dataDir.normalize();
		final Path path = inside(base, bucket);

		if (!base.equals(path.getParent())) {
			throw new InvalidPathException(bucket, "A bucket must be a single path segment");
		}

		return path;
	}

	public static Path object(Path dataDir, String bucket, String key) {
		return inside(bucket(dataDir, bucket), key);
	}

	private static Path inside(Path base, String name) {
		final Path path = base.resolve(name).normalize();

		if (path.equals(base) || !path.startsWith(base)) {
			throw new InvalidPathException(name, "The path must stay inside the storage directory");
		}

		return path;
	}
}
