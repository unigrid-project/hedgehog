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

package org.unigrid.hedgehog.client;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.io.InputStream;
import java.util.List;
import javax.net.ssl.SSLContext;
import lombok.SneakyThrows;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.jackson.internal.jackson.jaxrs.json.JacksonJaxbJsonProvider;
import org.unigrid.hedgehog.model.JsonConfiguration;
import org.unigrid.hedgehog.server.rest.JsonExceptionMapper;

public class RestClient implements AutoCloseable {
	private final Client client;
	private final String baseUrl;

	@SneakyThrows
	public RestClient(String host, int port, boolean isSecure) {
		final ClientConfig clientConfig = new ClientConfig();

		clientConfig.register(JacksonJaxbJsonProvider.class);
		clientConfig.register(new JsonConfiguration());
		clientConfig.register(JsonExceptionMapper.class);

		final SSLContext context = SSLContext.getInstance("ssl");
		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

		client = ClientBuilder.newBuilder()
			.hostnameVerifier((hostname, session) -> true) /* Accept all hostnames */
			.sslContext(context)
			.withConfig(clientConfig).build();

		if (isSecure) {
			baseUrl = String.format("https://%s:%d%%s", host, port);
		} else {
			baseUrl = String.format("http://%s:%d%%s", host, port);
		}
	}

	private void throwResponseOddity(Response response) throws ResponseOddityException {
		final List<Status> status = List.of(Status.OK, Status.NO_CONTENT,
			Status.NOT_FOUND, Status.UNAUTHORIZED
		);

		if (!status.contains(Status.fromStatusCode(response.getStatus()))) {
			throw new ResponseOddityException(response.getStatusInfo());
		}
	}

	public Response get(String location) throws ResponseOddityException {
		final Response response = client.target(String.format(baseUrl, location)).request().get();

		throwResponseOddity(response);
		return response;
	}

	public <T> T getEntity(String location, Class<T> clazz) throws ResponseOddityException {
		return client.target(String.format(baseUrl, location)).request().get(clazz);
	}

	public Response delete(String location) throws ResponseOddityException {
		final Response response = client.target(String.format(baseUrl, location)).request().delete();

		throwResponseOddity(response);
		return response;
	}

	public <T> Response post(String location, T entity) throws ResponseOddityException {
		final Response response = client.target(String.format(baseUrl, location)).request()
			.post(Entity.json(entity));

		throwResponseOddity(response);
		return response;
	}

	public Response postInputStream(String location, InputStream inputStream) throws ResponseOddityException {
		final Response response = client.target(String.format(baseUrl, location)).request()
			.post(Entity.entity(inputStream, MediaType.APPLICATION_OCTET_STREAM));

		throwResponseOddity(response);
		return response;
	}

	public <T> Response put(String location, T entity) throws ResponseOddityException {
		final Response response = client.target(String.format(baseUrl, location)).request()
			.put(Entity.json(entity));

		throwResponseOddity(response);
		return response;
	}

	public <T> Response putXml(String location, T entity) throws ResponseOddityException {
		final Response response = client.target(String.format(baseUrl, location)).request()
			.put(Entity.xml(entity));

		throwResponseOddity(response);
		return response;
	}

	public <T> Response putWithHeaders(String location, T entity, MultivaluedMap<String, Object> headers)
		throws ResponseOddityException {

		final Response response = client.target(String.format(baseUrl, location)).request()
			.headers(headers)
			.put(Entity.json(entity));

		throwResponseOddity(response);
		return response;
	}

	public Response putWithHeaders(String location, MultivaluedMap<String, Object> headers)
		throws ResponseOddityException {

		final Response response = client.target(String.format(baseUrl, location)).request()
			.headers(headers)
			.put(Entity.text(""));

		throwResponseOddity(response);
		return response;
	}

	@Override
	public void close() {
		client.close();
	}
}
