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

package org.unigrid.hedgehog.common.model;

import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.harawata.appdirs.AppDirsFactory;
import org.apache.commons.lang3.SystemUtils;

@RequiredArgsConstructor
public class ApplicationDirectory {
	@Getter private final String author;
	@Getter private final String name;

	public Path getUserConfigDir() {
		return Paths.get(AppDirsFactory.getInstance().getUserConfigDir(name, null, author, true));
	}

	public Path getUserDataDir() {
		return Paths.get(AppDirsFactory.getInstance().getUserDataDir(name, null, author, true));
	}

	public Path getUserLogDir() {
		return Paths.get(AppDirsFactory.getInstance().getUserLogDir(name, null, author));
	}

	public static ApplicationDirectory create() {
		String author = Version.getAuthor();
		String name = Version.getName();

		if (SystemUtils.IS_OS_UNIX && !SystemUtils.IS_OS_MAC) {
			author = author.toLowerCase();
			name = name.toLowerCase();
		}

		return new ApplicationDirectory(author, name);
	}
}
