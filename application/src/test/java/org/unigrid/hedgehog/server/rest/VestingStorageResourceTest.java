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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import lombok.SneakyThrows;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.unigrid.hedgehog.jqwik.TestFileOutput;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.VestingStorage;
import org.unigrid.hedgehog.model.spork.VestingStorage.SporkData.Vesting;

public class VestingStorageResourceTest extends BaseRestClientTest {

    @Provide
    public Arbitrary<Duration> durations() {
        return Arbitraries.longs()
                .between(1, 1000)
                .map(Duration::ofDays);
    }

    @Provide
    public Arbitrary<Instant> instants() {
        return Arbitraries.longs()
                .between(
                        Instant.now().minusSeconds(1_000_000).getEpochSecond(),
                        Instant.now().plusSeconds(1_000_000).getEpochSecond()
                )
                .map(Instant::ofEpochSecond);
    }

    @Provide
    public Arbitrary<Vesting> provideVesting(
            @ForAll @BigRange(min = "1", max = "1000000") @Scale(12) BigDecimal amount,
            @ForAll @ByteRange(min = 1) byte parts,
            @ForAll("durations") Duration duration,
            @ForAll("instants") Instant start) {

        Vesting v = new Vesting();
        v.setAmount(amount);
        v.setParts(parts);
        v.setDuration(duration);
        v.setStart(start);

        return Arbitraries.of(v);
    }

    @Property(tries = 30)
    public void shoulBeVerifiableInList(
            @ForAll("provideSignature") Signature signature,
            @ForAll @Size(max = 5) @UniqueElements List<@AlphaChars @StringLength(36) String> addresses,
            @ForAll @UniqueElements @Size(5) List<@From("provideVesting") Vesting> vests
    ) throws Exception {

        final String url = "/gridspork/vesting-storage/";
        final Response response = client.get(url);

        int originalNumVests = 0;
        int newVests = 0;

        if (Status.fromStatusCode(response.getStatus()) == Status.OK) {

            VestingStorage storage = response.readEntity(VestingStorage.class);

            final VestingStorage.SporkData data =
                    (VestingStorage.SporkData) storage.getData();

            originalNumVests = data.getVestingAddresses().size();
        }

        for (int i = 0; i < addresses.size(); i++) {

            MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
            headers.add("privateKey", signature.getPrivateKey());

            final Response putResponse =
                    client.putWithHeaders(
                            url + addresses.get(i),
                            Entity.json(vests.get(i)),
                            headers
                    );

            if (Status.fromStatusCode(putResponse.getStatus()) == Status.OK) {
                newVests++;
            }
        }

        if (newVests > 0) {

            VestingStorage storage = client.getEntity(url, VestingStorage.class);

            final VestingStorage.SporkData data =
                    (VestingStorage.SporkData) storage.getData();

            assertThat(
                    data.getVestingAddresses().size(),
                    equalTo(originalNumVests + newVests)
            );
        }
    }

    @Property(tries = 50)
    public void shoulBeAbleToGetVestingStorageSpork(
            @ForAll("provideSignature") Signature signature,
            @ForAll("provideVesting") Vesting vesting,
            @ForAll @AlphaChars @StringLength(36) String address
    ) throws Exception {

        final String url = "/gridspork/vesting-storage/%s".formatted(address);

        Status expectedStatusFromPut;

        if (Status.fromStatusCode(client.get(url).getStatus()) == Status.OK) {
            expectedStatusFromPut = Status.NO_CONTENT;
        } else {
            expectedStatusFromPut = Status.OK;
        }

        MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.add("privateKey", signature.getPrivateKey());

        final Response putResponse =
                client.putWithHeaders(
                        url,
                        Entity.json(vesting),
                        headers
                );

        assertThat(
                Status.fromStatusCode(putResponse.getStatus()),
                equalTo(expectedStatusFromPut)
        );

        TestFileOutput.outputJson(
                client.getEntity(url, String.class)
        );
    }
}