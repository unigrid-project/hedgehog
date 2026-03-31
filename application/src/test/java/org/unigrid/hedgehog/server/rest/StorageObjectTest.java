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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class StorageObjectTest extends BaseRestClientTest {

    @Property(tries = 30)
    void shouldCreateAndRetrieveObject(
            @ForAll @AlphaChars @StringLength(min = 1, max = 32) String bucket,
            @ForAll @AlphaChars @StringLength(min = 1, max = 32) String key,
            @ForAll byte[] data
    ) throws Exception {

        String url = "/storage-object/%s/%s".formatted(bucket, key);

        Response createResponse =
                client.post(url, Entity.entity(data, "application/octet-stream"));

        assertThat(createResponse.getStatus(), anyOf(equalTo(200), equalTo(404)));

        Response getResponse = client.get(url);

        if (getResponse.getStatus() == 200) {
            byte[] returned = getResponse.readEntity(byte[].class);
            assertThat(returned, notNullValue());
        }
    }

    @Property(tries = 20)
    void shouldDeleteObject(
            @ForAll @AlphaChars @StringLength(min = 1, max = 32) String bucket,
            @ForAll @AlphaChars @StringLength(min = 1, max = 32) String key
    ) throws Exception {

        String url = "/storage-object/%s/%s".formatted(bucket, key);

        Response deleteResponse = client.delete(url);

        assertThat(deleteResponse.getStatus(),
                anyOf(equalTo(204), equalTo(404), equalTo(500)));
    }
}