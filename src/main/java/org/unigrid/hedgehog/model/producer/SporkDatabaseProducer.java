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

package org.unigrid.hedgehog.model.producer;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Slf4j
@ApplicationScoped
public class SporkDatabaseProducer {
	@Inject private ApplicationDirectory applicationDirectory;
	@Inject private SporkDatabase sporkDatabase;

	private Path path() {
		return Path.of(applicationDirectory.getUserDataDir().toString(), SporkDatabase.SPORK_DB_FILE);
	}

	@Produces
	private SporkDatabase produce() {
		try {
			Files.createDirectories(applicationDirectory.getUserDataDir());
			sporkDatabase = SporkDatabase.load(path());

		} catch (IOException ex) {
			sporkDatabase = SporkDatabase.builder().build();
			log.atWarn().log("Creating fresh spork database: {}", ex.getMessage());
			log.atTrace().log(() -> ex.toString());
		}

		return sporkDatabase;
	}

	@PreDestroy
	private void destroy() {
		try {
			Files.createDirectories(applicationDirectory.getUserDataDir());
			SporkDatabase.persist(path(), sporkDatabase);

		} catch (Exception ex) {
			log.atWarn().log("Creating fresh spork database: {}", ex.getMessage());
			log.atTrace().log(() -> ex.toString());
		}
	}
}
