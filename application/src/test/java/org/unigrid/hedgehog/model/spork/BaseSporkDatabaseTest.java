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

  package org.unigrid.hedgehog.model.spork;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.ShortRange;
import net.jqwik.api.constraints.Size;

import org.apache.commons.lang3.reflect.FieldUtils;

import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.ApplicationDirectoryMockUp;

public class BaseSporkDatabaseTest extends BaseMockedWeldTest {

    private final GridSporkProvider gridSporkProvider = new GridSporkProvider();

    @BeforeContainer
    private static void beforeContainer() {
        new ApplicationDirectoryMockUp();
    }

    @Provide(ignoreExceptions = IllegalArgumentException.class)
    public Arbitrary<GridSpork> provideGridSpork(
            @ForAll GridSpork.Type gridSporkType,
            @ForAll @ShortRange(min = 0, max = 3) short flags,
            @ForAll @Size(min = 50, max = 60) byte[] signature,
            @ForAll Instant time,
            @ForAll Instant previousTime) {

        return gridSporkProvider.provide(gridSporkType, flags, signature, time, previousTime);
    }

    protected SporkDatabase db(Path path) {

        try {

            if (Files.exists(path)) {
                return SporkDatabase.load(path);
            }

            return new SporkDatabase();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected static void set(SporkDatabase sporkDatabase, GridSpork gridSpork) {

        try {

            switch (gridSpork.getType()) {

                case MINT_STORAGE:
                    FieldUtils.writeField(sporkDatabase, "mintStorage", gridSpork, true);
                    break;

                case MINT_SUPPLY:
                    FieldUtils.writeField(sporkDatabase, "mintSupply", gridSpork, true);
                    break;

                case VESTING_STORAGE:
                    FieldUtils.writeField(sporkDatabase, "vestingStorage", gridSpork, true);
                    break;

                case STATISTICS_PUBKEY:
                    FieldUtils.writeField(sporkDatabase, "statisticsPubKey", gridSpork, true);
                    break;

                default:
                    throw new IllegalArgumentException("Unsupported spork type passed.");
            }

        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}