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

package org.unigrid.hedgehog.common.model;

import java.nio.file.Path;
import java.util.List;
import net.jqwik.api.Example;
import org.apache.commons.lang3.SystemUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

/* Only paths are made here, no directory */
public class ApplicationDirectoryTest {
	private static final String PROPERTIES = "project.name=Unigrid Hedgehog\nproject.version=1.2.3\n";

	@Example
	public void shouldPlaceEveryDirectoryUnderTheNameOfTheApplication() {
		final ApplicationDirectory directory = new ApplicationDirectory("author", "application");
		final List<Path> paths = List.of(directory.getUserDataDir(), directory.getUserConfigDir(),
			directory.getUserCacheDir(), directory.getUserLogDir());

		assertThat(paths.stream().map(Path::toString).toList(), everyItem(containsString("application")));
	}

	@Example
	public void shouldNameTheDirectoriesInLowerCaseOnUnixButNotOnMac() {
		final ApplicationDirectory directory = VersionTest.withProperties(PROPERTIES, ApplicationDirectory::create);
		final boolean lowered = SystemUtils.IS_OS_UNIX && !SystemUtils.IS_OS_MAC;

		assertThat(directory.getAuthor(), equalTo(lowered ? "unigrid" : "Unigrid"));
		assertThat(directory.getName(), equalTo(lowered ? "hedgehog" : "Hedgehog"));
	}
}
