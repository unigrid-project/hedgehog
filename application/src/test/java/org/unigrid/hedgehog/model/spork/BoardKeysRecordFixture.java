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

package org.unigrid.hedgehog.model.spork;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Path;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import mockit.Mock;
import mockit.MockUp;

/* The record of the board keys that a command keeps, in a data directory in memory rather than in the one of the user
   who runs the tests, which may hold a record of a network */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BoardKeysRecordFixture {
	static {
		/* JMockit calls the fakes reflectively, but a test run only opens the packages of the tests it selects */
		BoardKeysRecordFixture.class.getModule().addOpens(BoardKeysRecordFixture.class.getPackageName(),
			MockUp.class.getModule());
	}

	public static Path inMemory() {
		final Path directory = Jimfs.newFileSystem(Configuration.unix()).getPath("/data");

		keepIn(directory);
		return directory;
	}

	public static void keepIn(Path directory) {
		new MockUp<BoardKeysRecord>() {
			@Mock Path directory() {
				return directory;
			}
		};
	}
}
