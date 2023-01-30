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
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import lombok.SneakyThrows;
import net.jqwik.api.Disabled;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.model.s3.entity.CopyObjectResult;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.model.s3.entity.ListBucketResult;

public class StorageObjectTest extends BaseRestClientTest {
	S3Mock api;
	RestClient client;

	String bucket = "testBucket";
	String key = "testObject";
	String copy = "copied";
	final int MAX_KEYS = 10000;

	@BeforeTry
	public void beforeTry() {
		api = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build();
		api.start();
		client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);
	}

	@AfterTry
	public void after() {
		api.shutdown();
		client.close();
	}

	@Example
	@Disabled
	@SneakyThrows
	public void shouldBe1() {
		final RestClient clientMock = new RestClient(server.getRest().getHostName(), 8001, false);

		CreateBucketConfiguration config = new CreateBucketConfiguration("TestConfig");

		clientMock.putXml("/" + bucket, config);
		client.putXml("/bucket/" + bucket, config);

		InputStream inputStream = new ByteArrayInputStream("Hello, World!".getBytes());
		InputStream inputStream2 = new ByteArrayInputStream("Hello, World!".getBytes());

		Response mockResponse = clientMock.postInputStream("/" + bucket + "/" + key, inputStream);
		Response response = client.postInputStream("/storage-object/" + bucket + "/" + key, inputStream2);

		assertThat(response.getStatus(), equalTo(mockResponse.getStatus()));

		clientMock.close();
	}

	@Example
	@SneakyThrows
	public void shouldBe2() {
		try {
			client.post("/storage-object/" + bucket + "/" + key, "");
		} catch (Exception e) {
			assertThat(e, isA(ResponseOddityException.class));
		}
	}

	@Example
	@Disabled
	@SneakyThrows
	public void shouldBe3() {
		ListBucketResult response = client.getEntity("/storage-object/list/" + bucket, ListBucketResult.class);

		assertThat(response.getName(), equalTo(bucket));
		assertThat(response.getDelimiter(), equalTo(""));
		assertThat(response.getPrefix(), equalTo(""));
		assertThat(response.getMaxKeys(), equalTo(MAX_KEYS));
	}

	@Example
	@Disabled
	@SneakyThrows
	public void shouldBe4() {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
		headers.add("x-amz-copy-source", "/" + bucket + "/" + key);

		Response response = client.putWithHeaders("/storage-object/" + bucket + "/" + copy, headers);
		CopyObjectResult result = response.readEntity(CopyObjectResult.class);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(result.getETag(), is(notNullValue()));
		assertThat(result.getLastModified().toString(), is(notNullValue()));
	}

	@Example
	@SneakyThrows
	public void shouldBe5() {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

		try {
			client.putWithHeaders("/storage-object/testBukcet/testObjecyt", headers);
		} catch (Exception e) {
			assertThat(e.getMessage(), containsString("400 Bad Request"));
		}
	}

	@Example
	@Disabled
	@SneakyThrows
	public void shouldBe6() {
		Response response = client.get("/storage-object/" + bucket + "/" + key);

		assertThat(response.getStatus(), equalTo(200));
		// file should not be empty
		assertThat(response.getLength(), greaterThan(0));
	}

	@Example
	@Disabled
	@SneakyThrows
	public void shouldBe7() {
		try {
			client.get("/storage-object/" + bucket + "/testtest");
		} catch (Exception e) {
			assertThat(e, isA(ResponseOddityException.class));
			assertThat(e.getMessage(), containsString("404 Not Found"));
		}
	}

	@Example
	@Disabled
	@SneakyThrows
	public void shouldBe8() {
		Response response = client.delete("/storage-object/" + bucket + "/" + copy);

		assertThat(response.getLength(), equalTo(-1));
		assertThat(response.getStatus(), equalTo(204));

		client.delete("/bucket/" + bucket);
	}
}
