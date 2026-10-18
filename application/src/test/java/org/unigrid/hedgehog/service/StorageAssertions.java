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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.FileSystem;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class StorageAssertions {
	@FunctionalInterface
	public interface StorageCall {
		void call() throws Exception;
	}

	/*
	 * An in-memory file system: Path.toFile() refuses its paths, so a service that let a name through
	 * could not reach the real disk even by mistake
	 */
	public static Path inMemoryDataDir() {
		final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
		return fileSystem.getPath("/s3data");
	}

	@SneakyThrows
	public static void assertRejected(StorageCall call) {
		Throwable thrown = null;

		try {
			call.call();
		} catch (Exception ex) {
			thrown = ex;
		}

		assertThat(thrown, isA(InvalidPathException.class));
	}
}
