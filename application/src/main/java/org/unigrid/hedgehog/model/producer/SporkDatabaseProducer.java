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
 package org.unigrid.hedgehog.model.producer;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.SerializationException;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@ApplicationScoped
public class SporkDatabaseProducer {

    private static final Logger log =
            LoggerFactory.getLogger(SporkDatabaseProducer.class);

    @Inject
    private ApplicationDirectory applicationDirectory;

    private final AtomicReference<SporkDatabase> sporkDatabase =
            new AtomicReference<>(null);

    private Path path() {
        return Path.of(
                applicationDirectory.getUserDataDir().toString(),
                SporkDatabase.SPORK_DB_FILE
        );
    }

    @Produces
    private SporkDatabase produce() {

        if (Objects.isNull(sporkDatabase.get())) {

            try {
                Files.createDirectories(applicationDirectory.getUserDataDir());
                sporkDatabase.set(SporkDatabase.load(path()));

            } catch (IOException ex) {

                sporkDatabase.set(new SporkDatabase());
                log.warn("Creating fresh spork database: {}", ex.getMessage());

            } catch (ClassCastException | SerializationException ex) {

                sporkDatabase.set(new SporkDatabase());
                log.warn("Database serialization incompatibility, using fresh database: {}",
                        ex.getMessage());
            }
        }

        return sporkDatabase.get();
    }

    @PreDestroy
    private void destroy() {

        try {
            Files.createDirectories(applicationDirectory.getUserDataDir());
            SporkDatabase.persist(path(), sporkDatabase.get());

        } catch (Exception ex) {
            log.warn("Saving of spork database failed: {}", ex.getMessage());
        }
    }
}