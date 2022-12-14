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
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.client.RestClient;

public class StorageObjectTest extends BaseRestClientTest {
	RestClient client;

	S3Mock api;

	@BeforeTry
	public void beforeTry() {
		client = new RestClient(server.getRest().getHostName(), server.getRest().getPort());

		api = new S3Mock.Builder().withPort(8001).withInMemoryBackend().build();
		api.start();
	}

	@AfterTry
	public void after() {
		client.close();
		api.shutdown();
	}

	@Example
	@SneakyThrows
	public void shouldCreateObject() {
		InputStream inputStream = new ByteArrayInputStream("Hello, World!".getBytes());
		Response response = client.putInputStream("/storage-object/testOBJECT", inputStream);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(response.getHeaderString("ETag"), notNullValue());
	}

	@Example
	@SneakyThrows
	public void shouldContainInputStream() {
		try {
			client.put("/bucket", null);
		} catch (Exception e) {
			assertThat(e, isA(IllegalStateException.class));
		}
	}

	@Example
	@SneakyThrows
	public void shouldListAndReturnXML() {
		Response response = client.get("/storage-object/list");

		String entity = response.readEntity(String.class);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(response.getLength(), greaterThan(0));

		assertThat(entity, containsString("name"));
		assertThat(entity, containsString("prefix"));
		assertThat(entity, containsString("marker"));
		assertThat(entity, containsString("maxKeys"));
		assertThat(entity, containsString("isTruncated"));
		assertThat(entity, containsString("contents"));
	}

	@Example
	@SneakyThrows
	public void shouldCopyAndReturnXML() {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
		headers.add("x-amz-copy-source", "value1");

		Response response = client.putEmptyBody("/storage-object/testBukcet/testObjecyt", headers);

		String entity = response.readEntity(String.class);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(response.getLength(), greaterThan(0));

		assertThat(entity, containsString("eTag"));
		assertThat(entity, containsString("lastModified"));

	}

	@Example
	@SneakyThrows
	public void shouldContainHeader() {
		MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

		try {
			client.putEmptyBody("/storage-object/testBukcet/testObjecyt", headers);
		} catch (Exception e) {
			assertThat(e.getMessage(), containsString("400 Bad Request"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldReturnData() {
		Response response = client.get("/storage-object/key1");

		assertThat(response.getStatus(), equalTo(200));
		assertThat(response.getLength(), greaterThan(0));
	}

	@Example
	@SneakyThrows
	public void shouldReturnNoContent() {
		Response response = client.delete("/storage-object/key2");

		assertThat(response.getLength(), equalTo(-1));
		assertThat(response.getStatus(), equalTo(204));
	}
}
