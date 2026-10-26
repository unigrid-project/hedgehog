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

package org.unigrid.hedgehog.model;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.file.Path;
import lombok.Getter;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

/* A user data directory on an in-memory file system, so nothing written under it can reach the real disk */
public class InMemoryDataDirectoryMockUp extends MockUp<ApplicationDirectory> {
	@Getter private final Path userDataDir = Jimfs.newFileSystem(Configuration.unix()).getPath("/data");

	@Mock
	public Path getUserDataDir() {
		return userDataDir;
	}
}
