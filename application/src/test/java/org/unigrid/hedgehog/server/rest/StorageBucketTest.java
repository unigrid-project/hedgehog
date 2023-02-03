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

package org.unigrid.hedgehog.server.rest;

import io.findify.s3mock.S3Mock;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.AfterProperty;
import org.unigrid.hedgehog.client.RestClient;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import net.jqwik.api.constraints.NotBlank;
import lombok.Data;
import org.unigrid.hedgehog.client.ResponseOddityException;
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

	@Data
	public static class TestBucket {
		private Bucket bucket;
		private Bucket mockBucket;
	}

	@Provide
	public Arbitrary<List<TestBucket>> provideBuckets(@ForAll
		@Size(min = 1, max = 10) List<@NotBlank @AlphaChars String> bucketNames,
		@ForAll @NotBlank @AlphaChars String configurationName) throws ResponseOddityException {

		final RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);
		final RestClient clientMock = new RestClient(server.getRest().getHostName(), 8001, false);

		final CreateBucketConfiguration config = new CreateBucketConfiguration(configurationName);
		final List<TestBucket> testBuckets = new ArrayList<>();

		for (String bucketName : bucketNames) {
			Response response = client.putXml("/bucket/" + bucketName, config);
			Response mockResponse = clientMock.putXml("/" + bucketName, config);
			assertThat(response.getStatus(), equalTo(mockResponse.getStatus()));
		}

		final ListAllMyBucketsResult buckets = client.getEntity("/bucket/list", ListAllMyBucketsResult.class);
		Response response = clientMock.get("/");
		final ListAllMyBucketsResult mockBuckets = response.readEntity(ListAllMyBucketsResult.class);

		List<Bucket> allBuckets = buckets.getBuckets();
		List<Bucket> allMockBuckets = mockBuckets.getBuckets();

		Collections.sort(allBuckets, (Bucket b1, Bucket b2) -> b1.getName().compareTo(b2.getName()));
		Collections.sort(allMockBuckets, (Bucket b1, Bucket b2) -> b1.getName().compareTo(b2.getName()));

		Response deleteResponse = client.delete("/bucket/" + allBuckets.get(0).getName());
		Response mockDeleteResponse = clientMock.delete("/" + allMockBuckets.get(0).getName());

		assertThat(deleteResponse.getLength(), equalTo(mockDeleteResponse.getLength()));
		assertThat(deleteResponse.getStatus(), equalTo(mockDeleteResponse.getStatus()));

		for (int i = 0; i < allBuckets.size(); i++) {
			final TestBucket testBucket = new TestBucket();

			testBucket.setBucket(allBuckets.get(i));
			testBucket.setMockBucket(allMockBuckets.get(i));
			testBuckets.add(testBucket);
		}

		client.close();
		clientMock.close();
		return Arbitraries.shuffle(testBuckets);
	}

	@Property(tries = 5)
	@SneakyThrows
	public void shouldBe1(@ForAll("provideBuckets") List<TestBucket> testBuckets) {
		for (TestBucket ts : testBuckets) {
			assertThat(ts.bucket.getName(), is(equalTo(ts.mockBucket.getName())));
		}
	}

	@Example
	@SneakyThrows
	public void shouldBe2() {
		RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);

		try {
			client.put("/bucket/test", "");
		} catch (Exception e) {
			assertThat(e, isA(ResponseOddityException.class));
		}

		client.close();
	}

	@Example
	@SneakyThrows
	public void shouldBe3() {
		RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);

		try {
			client.delete("/bucketdelete");
		} catch (Exception e) {
			assertThat(e, isA(ResponseOddityException.class));
		}

		client.close();
	}
}
