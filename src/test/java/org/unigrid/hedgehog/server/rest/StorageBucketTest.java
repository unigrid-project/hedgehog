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
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.AfterTry;
import org.apache.commons.lang3.RandomStringUtils;
import org.unigrid.hedgehog.client.RestClient;
import static org.burningwave.core.assembler.StaticComponentContainer.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.model.util.ApplicationLogLevel;

public class StorageBucketTest extends BaseRestClientTest {
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

//	@BeforeContainer
//	public static void beforeEverything() {
//		ApplicationLogLevel.configure(5);
//		Modules.exportAllToAll();
//	}
	@Example
	@SneakyThrows
	public void shouldReturnLocation() {
		CreateBucketConfiguration bucketConfiguration = new CreateBucketConfiguration("TESTTTTT!!!!!");

		File tempXML = File.createTempFile(RandomStringUtils.randomAlphabetic(20), ".xml");
		JAXBContext jaxbContext = JAXBContext.newInstance(CreateBucketConfiguration.class);
		Marshaller jaxbMarshaller = jaxbContext.createMarshaller();

		jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		jaxbMarshaller.marshal(bucketConfiguration, tempXML);
		jaxbMarshaller.marshal(bucketConfiguration, System.out);

		Response response = client.put("/bucket", tempXML);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(response.getHeaderString("Location"), notNullValue());
	}

	@Example
	@SneakyThrows
	public void shouldContainFile() {
		try {
			client.put("/bucket", null);
		} catch (Exception e) {
			assertThat(e, isA(IllegalStateException.class));
		}
	}

	@Example
	@SneakyThrows
	public void shouldReturnXML() {
		Response response = client.get("/bucket/list");

		String entity = response.readEntity(String.class);

		assertThat(response.getStatus(), equalTo(200));
		assertThat(response.getLength(), greaterThan(0));

		assertThat(entity, containsString("buckets"));
		assertThat(entity, containsString("owner"));
	}

	@Example
	@SneakyThrows
	public void shouldReturnNoContent() {
		Response response = client.delete("/bucket/delete");

		assertThat(response.getLength(), equalTo(-1));
		assertThat(response.getStatus(), equalTo(204));
	}

	@Example
	@SneakyThrows
	public void s3Request() {
		// Set the name of the new bucket
		String bucketName = "my-new-bucket";

// Set the URL of the Amazon S3 API endpoint for creating a new bucket
		String urlString = "http://localhost:8001/" + bucketName;

// Create a new URL object from the URL string
		URL url = new URL(urlString);

// Open a connection to the URL
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();

// Set the request method to "PUT"
		connection.setRequestMethod("PUT");

// Set the request headers
//		connection.setRequestProperty("Content-Type", "application/xml");
//		connection.setRequestProperty("Date", java.time.Instant.now().toString());

// Send the request to the Amazon S3 API
		connection.connect();
		// send the request and get the response code
		int responseCode = connection.getResponseCode();
		String location = connection.getHeaderField("Location");

		// print the response code
		System.out.println("Response code: " + responseCode);
		System.out.println("Header: " + location);

// Close the connection
		connection.disconnect();
	}
}
