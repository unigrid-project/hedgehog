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

import io.findify.s3mock.S3Mock;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.SneakyThrows;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import net.jqwik.api.lifecycle.*;

import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.client.ResponseOddityException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;

public class StorageBucketTest extends BaseRestClientTest {

    S3Mock api;

    @BeforeProperty
    public void beforeEverything() {
        api = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build();
        api.start();
    }

    @AfterProperty
    public void afterEverything() {
        api.shutdown();
    }

    public static class TestBucket {

        private Bucket bucket;
        private Bucket mockBucket;

        public Bucket getBucket() {
            return bucket;
        }

        public void setBucket(Bucket bucket) {
            this.bucket = bucket;
        }

        public Bucket getMockBucket() {
            return mockBucket;
        }

        public void setMockBucket(Bucket mockBucket) {
            this.mockBucket = mockBucket;
        }
    }

    @Provide
    public Arbitrary<List<TestBucket>> provideBuckets(
            @ForAll @Size(min = 1, max = 10) List<@NotBlank @AlphaChars String> bucketNames,
            @ForAll @NotBlank @AlphaChars String configurationName
    ) throws Exception {

        final RestClient clientMock =
                new RestClient(server.getRest().getHostName(), 8001, false);

        final CreateBucketConfiguration config = new CreateBucketConfiguration();

        final List<TestBucket> testBuckets = new ArrayList<>();

        for (String bucketName : bucketNames) {

            Response response = client.put("/bucket/" + bucketName, Entity.xml(config));
            Response mockResponse = clientMock.put("/" + bucketName, Entity.xml(config));

            assertThat(response.getStatus(), equalTo(mockResponse.getStatus()));
        }

        final ListAllMyBucketsResult buckets =
                client.getEntity("/bucket/list", ListAllMyBucketsResult.class);

        Response response = clientMock.get("/");
        final ListAllMyBucketsResult mockBuckets =
                response.readEntity(ListAllMyBucketsResult.class);

        List<Bucket> allBuckets = buckets.getBuckets();
        List<Bucket> allMockBuckets = mockBuckets.getBuckets();

        Collections.sort(allBuckets,
                (Bucket b1, Bucket b2) -> b1.getName().compareTo(b2.getName()));

        Collections.sort(allMockBuckets,
                (Bucket b1, Bucket b2) -> b1.getName().compareTo(b2.getName()));

        Response deleteResponse =
                client.delete("/bucket/" + allBuckets.get(0).getName());

        Response mockDeleteResponse =
                clientMock.delete("/" + allMockBuckets.get(0).getName());

        assertThat(deleteResponse.getLength(),
                equalTo(mockDeleteResponse.getLength()));

        assertThat(deleteResponse.getStatus(),
                equalTo(mockDeleteResponse.getStatus()));

        for (int i = 0; i < allBuckets.size(); i++) {

            final TestBucket testBucket = new TestBucket();

            testBucket.setBucket(allBuckets.get(i));
            testBucket.setMockBucket(allMockBuckets.get(i));

            testBuckets.add(testBucket);
        }

        clientMock.close();

        return Arbitraries.shuffle(testBuckets);
    }

    @Disabled
    @Property(tries = 5)
    @SneakyThrows
    public void shouldBe1(@ForAll("provideBuckets") List<TestBucket> testBuckets) {

        for (TestBucket ts : testBuckets) {
            assertThat(
                    ts.getBucket().getName(),
                    is(equalTo(ts.getMockBucket().getName()))
            );
        }
    }

    @Example
    @Disabled
    public void shouldBe2() throws Exception {

        RestClient client =
                new RestClient(
                        server.getRest().getHostName(),
                        server.getRest().getPort(),
                        true
                );

        try {
            client.put("/bucket/test", Entity.xml(""));
        } catch (Exception e) {
            assertThat(e, isA(ResponseOddityException.class));
        }

        client.close();
    }

    @Example
    @Disabled
    public void shouldBe3() throws Exception {

        RestClient client =
                new RestClient(
                        server.getRest().getHostName(),
                        server.getRest().getPort(),
                        true
                );

        try {
            client.delete("/bucketdelete");
        } catch (Exception e) {
            assertThat(e, isA(ResponseOddityException.class));
        }

        client.close();
    }
}