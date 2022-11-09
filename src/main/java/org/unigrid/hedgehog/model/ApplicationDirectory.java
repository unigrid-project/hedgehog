/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.harawata.appdirs.AppDirsFactory;
import org.apache.commons.lang3.SystemUtils;

@ApplicationScoped
public class ApplicationDirectory {
	private String author;
	private String name;

	@PostConstruct
	private void init() {
		author = VersionProvider.getAuthor();
		name = VersionProvider.getName();

		if (SystemUtils.IS_OS_UNIX && !SystemUtils.IS_OS_MAC) {
			author = author.toLowerCase();
			name = name.toLowerCase();
		}
	}

	public Path getUserConfigDir() {
		return Paths.get(AppDirsFactory.getInstance().getUserConfigDir(name, null, author, true));
	}

	public Path getUserLogDir() {
		return Paths.get(AppDirsFactory.getInstance().getUserLogDir(name, null, author));
	}

	public Path getUserDataDir() {
		return Paths.get(AppDirsFactory.getInstance().getUserDataDir(name, null, author, true));
	}
}
