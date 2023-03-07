/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;

public class ApplicationDirectoryMockUp extends MockUp<ApplicationDirectory> {
	private final Path userConfigDir;
	private final Path userDataDir;
	private final Path userLogDir;

	@SneakyThrows
	public ApplicationDirectoryMockUp() {
		userConfigDir = Files.createTempDirectory("hhg-config-");
		userDataDir = Files.createTempDirectory("hhg-data-");
		userLogDir = Files.createTempDirectory("hhg.logs-");
	}

	@Mock
	public Path getUserConfigDir() {
		return userConfigDir;
	}

	@Mock
	public Path getUserDataDir() {
		return userDataDir;
	}

	@Mock
	public Path getUserLogDir() {
		return userLogDir;
	}
}
