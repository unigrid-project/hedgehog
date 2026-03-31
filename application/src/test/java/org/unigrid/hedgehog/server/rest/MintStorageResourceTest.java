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
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import java.math.BigDecimal;
import java.util.List;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData;
import org.unigrid.hedgehog.client.ResponseOddityException;
import com.fasterxml.jackson.core.JsonProcessingException;

public class MintStorageResourceTest extends BaseRestClientTest {

    // --- Location class ---
    public static class Location {
        private final int height;
        private final Address address;

        public Location(int height, Address address) {
            this.height = height;
            this.address = address;
        }

        public int getHeight() { return height; }
        public Address getAddress() { return address; }
    }

    // --- Address class ---
    public static class Address {
        private final String wif;

        public Address(String wif) { this.wif = wif; }
        public String getWif() { return wif; }
    }

    // --- Arbitrary Location provider ---
    @Provide
    public Arbitrary<Location> provideLocation(
            @ForAll @AlphaChars @StringLength(36) String address,
            @ForAll @Positive int height
    ) {
        return Arbitraries.of(new Location(height, new Address(address)));
    }

    // --- Test: Verifiable in list ---
    @Property(tries = 30)
    public void shoulBeVerifiableInList(
            @ForAll("provideSignature") Signature signature,
            @ForAll @UniqueElements @Size(5) List<Location> locations,
            @ForAll @BigRange(min = "0", max = "1000000") BigDecimal amount
    ) throws ResponseOddityException {

        final String url = "/gridspork/mint-storage/";
        final Response response = client.get(url);
        int originalNumMints = 0;
        int newMints = 0;

        if (Status.fromStatusCode(response.getStatus()) == Status.OK) {
            final SporkData data = client.getEntity(url, MintStorage.class).getData();
            originalNumMints = data.getMints().size();
        }

        for (Location l : locations) {
            MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
            headers.putSingle("privateKey", signature.getPrivateKey());

            final Response putResponse = client.putWithHeaders(
                    url + l.getAddress().getWif() + "/" + l.getHeight(),
                    Entity.text(amount),
                    headers
            );

            if (Status.fromStatusCode(putResponse.getStatus()) == Status.OK) {
                newMints++;
            }
        }

        if (newMints > 0) {
            final SporkData data = client.getEntity(url, MintStorage.class).getData();
            assertThat(data.getMints().size(), equalTo(originalNumMints + newMints));
        }
    }

    // --- Test: Get specific mint storage spork ---
    @Property(tries = 50)
    public void shoulBeAbleToGetMintStorageSpork(
            @ForAll("provideSignature") Signature signature,
            @ForAll("provideLocation") Location location,
            @ForAll @BigRange(min = "0") BigDecimal amount
    ) throws ResponseOddityException, JsonProcessingException {

        final int height = location.getHeight();
        final String wif = location.getAddress().getWif();
        final String url = "/gridspork/mint-storage/%s/%d".formatted(wif, height);

        Status expectedStatusFromPut;
        if (Status.fromStatusCode(client.get(url).getStatus()) != Status.OK) {
            expectedStatusFromPut = Status.OK;
        } else {
            expectedStatusFromPut = Status.NO_CONTENT;
        }

        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.putSingle("privateKey", signature.getPrivateKey());

        final Response putResponse = client.putWithHeaders(url, Entity.text(amount), headers);

        assertThat(Status.fromStatusCode(putResponse.getStatus()), equalTo(expectedStatusFromPut));

        TestFileOutput.outputJson(client.getEntity(url, String.class));
    }
}