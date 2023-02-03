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
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.Provide;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
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
	RestClient clientMock;

	String copySuffix = "_copied";
	final int MAX_KEYS = 10000;

	@BeforeProperty
	public void beforeTry() {
		api = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build();
		api.start();
		client = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);
		clientMock = new RestClient(server.getRest().getHostName(), 8001, false);
	}

	@AfterProperty
	public void after() {
		api.shutdown();
		client.close();
		clientMock.close();
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TestObject {
		private String bucket;
		private String key;
	}

	@Provide
	public Arbitrary<String> nameProvider(@ForAll @AlphaChars @StringLength(max = 20, min = 1) String key) {
		return Arbitraries.of(key);
	}

	@Provide
	public Arbitrary<CreateBucketConfiguration> bucketConfigurationProvider(@ForAll("nameProvider") String configurationName) {
		return Arbitraries.of(new CreateBucketConfiguration(configurationName));
	}

	@Provide
	public Arbitrary<String> bucketProvider(@ForAll("nameProvider") String bucketName,
		@ForAll("bucketConfigurationProvider") CreateBucketConfiguration config) throws ResponseOddityException {

		RestClient client2 = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);
		RestClient clientMock2 = new RestClient(server.getRest().getHostName(), 8001, false);

		client2.putXml("/bucket/" + bucketName, config);
		clientMock2.putXml("/" + bucketName, config);

		client2.close();
		clientMock2.close();

		return Arbitraries.of(bucketName);
	}

	@Provide
	public Arbitrary<List<TestObject>> keyProvider(@ForAll("nameProvider") String content,
		@ForAll("nameProvider") String keyName, @ForAll("bucketProvider") String bucket) throws ResponseOddityException {

		RestClient client2 = new RestClient(server.getRest().getHostName(), server.getRest().getPort(), true);
		RestClient clientMock2 = new RestClient(server.getRest().getHostName(), 8001, false);

		InputStream inputStream = new ByteArrayInputStream(content.getBytes());
		InputStream inputStream2 = new ByteArrayInputStream(content.getBytes());

		clientMock2.postInputStream("/" + bucket + "/" + keyName, inputStream);
		client2.postInputStream("/storage-object/" + bucket + "/" + keyName, inputStream2);

		final List<TestObject> testObjects = new ArrayList<>();
		testObjects.add(new TestObject(bucket, keyName));

		client2.close();
		clientMock2.close();

		return Arbitraries.shuffle(testObjects);
	}

	@Property(tries = 10)
	public void shouldBeAbleToCreateObject(@ForAll("nameProvider") String content, @ForAll("nameProvider") String key,
		@ForAll("bucketProvider") String bucket) throws ResponseOddityException {

		InputStream inputStream = new ByteArrayInputStream(content.getBytes());
		InputStream inputStream2 = new ByteArrayInputStream(content.getBytes());

		Response mockResponse = clientMock.postInputStream("/" + bucket + "/" + key, inputStream);
		Response response = client.postInputStream("/storage-object/" + bucket + "/" + key, inputStream2);

		assertThat(response.getStatus(), equalTo(mockResponse.getStatus()));
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldContainInputStream(@ForAll("bucketConfigurationProvider") CreateBucketConfiguration config,
		@ForAll("nameProvider") String key, @ForAll("nameProvider") String bucketName) {

		try {
			client.putXml("/bucket/" + bucketName, config);
			client.post("/storage-object/" + bucketName + "/" + key, "");
		} catch (Exception e) {
			assertThat(e, isA(ResponseOddityException.class));
		}
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldListAllObjects(@ForAll("bucketProvider") String bucket) {
		ListBucketResult response = client.getEntity("/storage-object/list/" + bucket, ListBucketResult.class);

		assertThat(response.getName(), equalTo(bucket));
		assertThat(response.getDelimiter(), equalTo(""));
		assertThat(response.getPrefix(), equalTo(""));
		assertThat(response.getMaxKeys(), equalTo(MAX_KEYS));
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldCopyObject(@ForAll("keyProvider") List<TestObject> testObject) {
		String bucket = testObject.get(0).getBucket();
		String key = testObject.get(0).getKey();

		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
		headers.add("x-amz-copy-source", "/" + bucket + "/" + key);

		Response response = client.putWithHeaders("/storage-object/" + bucket + "/" + key + copySuffix, headers);
		CopyObjectResult result = response.readEntity(CopyObjectResult.class);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(result.getETag(), is(notNullValue()));
		assertThat(result.getLastModified().toString(), is(notNullValue()));
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldContainHeader(@ForAll("nameProvider") String bucketName, @ForAll("nameProvider") String key) {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

		try {
			client.putWithHeaders("/storage-object/" + bucketName + "/" + key, headers);
		} catch (Exception e) {
			assertThat(e.getMessage(), containsString("400 Bad Request"));
		}
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldGetAnObject(@ForAll("keyProvider") List<TestObject> testObject) {
		Response response = client.get("/storage-object/" + testObject.get(0).getBucket() + "/"
			+ testObject.get(0).getKey());

		assertThat(response.getStatus(), equalTo(200));
	}

	@Property(tries = 10)
	@SneakyThrows
	public void shouldObjectExist(@ForAll("nameProvider") String bucketName) {
		try {
			client.get("/storage-object/" + bucketName + "/testtest");
		} catch (Exception e) {
			assertThat(e, isA(ResponseOddityException.class));
			assertThat(e.getMessage(), containsString("404 Not Found"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldDeleteAnObject(@ForAll("keyProvider") List<TestObject> testObject) {
		Response response = client.delete("/storage-object/" + testObject.get(0).getBucket() + "/"
			+ testObject.get(0).getKey());

		assertThat(response.getLength(), equalTo(-1));
		assertThat(response.getStatus(), equalTo(204));

		client.delete("/bucket/" + testObject.get(0).getBucket());
	}
}
