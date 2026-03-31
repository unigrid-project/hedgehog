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
 package org.unigrid.hedgehog.server.rest;

import jakarta.inject.Inject;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;

import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.MintStorage.Address;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo.Overview;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.client.ResponseOddityException;

public class GridSporkResourceTest extends BaseRestClientTest {

    @Inject
    private SporkDatabase sporkDatabase;

    @Inject
    private RestClient client;

    @Example
    public void shouldBeAbleToGetGridSporkOverview() {
        final Instant now = Instant.now();

        try {
            // --- Hämta översikt ---
            SporkDatabaseInfo info = client.get("/gridspork").readEntity(SporkDatabaseInfo.class);

            // --- Hämta private mintStorageEntries via reflection ---
            Field mintStorageEntriesField = SporkDatabaseInfo.class.getDeclaredField("mintStorageEntries");
            mintStorageEntriesField.setAccessible(true);
            Overview overview = (Overview) mintStorageEntriesField.get(info);

            // --- Läs privata fält i Overview ---
            Field lastChangedField = Overview.class.getDeclaredField("lastChanged");
            lastChangedField.setAccessible(true);
            String lastChanged = (String) lastChangedField.get(overview);

            Field amountField = Overview.class.getDeclaredField("amount");
            amountField.setAccessible(true);
            int amount = (int) amountField.get(overview);

            // --- Kontrollera initial värde ---
            assertThat(lastChanged, equalTo(SporkDatabaseInfo.LASTCHANGED_NEVER));
            assertThat(amount, equalTo(0));

            // --- Hämta eller skapa mintStorage via reflection ---
            Field mintStorageField = SporkDatabase.class.getDeclaredField("mintStorage");
            mintStorageField.setAccessible(true);
            MintStorage mintStorage = (MintStorage) mintStorageField.get(sporkDatabase);

            if (mintStorage == null) {
                mintStorage = new MintStorage();
                mintStorageField.set(sporkDatabase, mintStorage);
            }

            mintStorage.setData(new SporkData());
            mintStorage.setTimeStamp(now);

            SporkData data = mintStorage.getData();
            data.setMints(new HashMap<>());

            // --- Skapa Location med intern Address ---
            Location loc = new Location();
            loc.setAddress(new Address("0123456789"));
            loc.setHeight(1337);

            data.getMints().put(loc, BigDecimal.ONE);

            // --- Läs översikten igen via reflection ---
            info = client.get("/gridspork").readEntity(SporkDatabaseInfo.class);
            overview = (Overview) mintStorageEntriesField.get(info);
            lastChanged = (String) lastChangedField.get(overview);
            amount = (int) amountField.get(overview);

            assertThat(lastChanged, equalTo(now.toString()));
            assertThat(amount, equalTo(1));

        } catch (ResponseOddityException | NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}