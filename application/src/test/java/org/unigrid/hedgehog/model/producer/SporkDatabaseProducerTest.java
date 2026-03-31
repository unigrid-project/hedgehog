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

import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import lombok.Cleanup;
import lombok.Data;

import net.jqwik.api.Example;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.reflect.FieldUtils;

import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.spork.BaseSporkDatabaseTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.util.Reflection;

public class SporkDatabaseProducerTest extends BaseSporkDatabaseTest {

    @Inject
    private ApplicationDirectory applicationDirectory;

    @Data
    public static class BrokenSporkDatabase implements Serializable {
        private int justSomeRandomProperty = 42;
    }

    private static <D extends Serializable> void persist(Path path, D database) throws IOException {

        @Cleanup
        OutputStream stream = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );

        SerializationUtils.serialize(database, stream);
    }

    @Example
    public void shouldReplaceIncompatibleDatabase() {

        try {

            Files.createDirectories(applicationDirectory.getUserDataDir());

            Path path = Path.of(
                    applicationDirectory.getUserDataDir().toString(),
                    SporkDatabase.SPORK_DB_FILE
            );

            BrokenSporkDatabase brokenSporkDatabase = new BrokenSporkDatabase();

            persist(path, brokenSporkDatabase);

            long originalFileSize = Files.size(path);

            SporkDatabaseProducer producer = new SporkDatabaseProducer();

            FieldUtils.writeField(producer, "applicationDirectory", applicationDirectory, true);

            SporkDatabase db = Reflection.invoke(producer, "produce");

            persist(path, db);

            long newFileSize = Files.size(path);

            assertThat(originalFileSize, not(equalTo(newFileSize)));

        } catch (
                IOException |
                IllegalAccessException |
                InvocationTargetException |
                NoSuchMethodException |
                ClassNotFoundException e
        ) {
            throw new RuntimeException(e);
        }
    }
} 