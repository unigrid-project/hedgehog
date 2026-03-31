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

	import java.util.Objects;
	import java.util.Set;

	import javax.net.ssl.SSLContext;

	import org.glassfish.jersey.jackson.JacksonFeature;
	import org.unigrid.hedgehog.model.JsonConfiguration;
	import org.unigrid.hedgehog.server.rest.JsonExceptionMapper;

	import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
	import jakarta.ws.rs.client.Client;
	import jakarta.ws.rs.client.ClientBuilder;
	import jakarta.ws.rs.client.Entity;
	import jakarta.ws.rs.core.MultivaluedMap;
	import jakarta.ws.rs.core.Response;
	import jakarta.ws.rs.core.Response.Status;
	
	public class RestClient implements AutoCloseable {
	
		private final Client client;
		private final String baseUrl;
	
		public RestClient(String host, int port, boolean isSecure) throws Exception {
			Objects.requireNonNull(host, "host must not be null");
	
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
	
			client = ClientBuilder.newBuilder()
					.hostnameVerifier((hostname, session) -> true)
					.sslContext(context)
					.build()
					.register(JacksonFeature.class)
					.register(new JsonConfiguration())
					.register(JsonExceptionMapper.class);
	
			String protocol = isSecure ? "https" : "http";
			baseUrl = String.format("%s://%s:%d%s", protocol, host, port, "%s");
		}
	
		private void throwResponseOddity(Response response) throws ResponseOddityException {
			final Set<Status> allowedStatuses = Set.of(
					Status.ACCEPTED,
					Status.CREATED,
					Status.OK,
					Status.NO_CONTENT,
					Status.NOT_FOUND,
					Status.UNAUTHORIZED
			);
	
			Status status = Status.fromStatusCode(response.getStatus());
			if (!allowedStatuses.contains(status)) {
				throw new ResponseOddityException(
						response.getStatus(),
						response.getStatusInfo().getReasonPhrase()
				);
			}
		}
	
		public Response get(String location) throws ResponseOddityException {
			Response response = client.target(String.format(baseUrl, location))
					.request()
					.get();
			throwResponseOddity(response);
			return response;
		}
	
		public <T> T getEntity(String location, Class<T> clazz) {
			return client.target(String.format(baseUrl, location))
					.request()
					.get(clazz);
		}
	
		public Response delete(String location) throws ResponseOddityException {
			Response response = client.target(String.format(baseUrl, location))
					.request()
					.delete();
			throwResponseOddity(response);
			return response;
		}
	
		public <T> Response post(String location, Entity<T> entity) throws ResponseOddityException {
			Response response = client.target(String.format(baseUrl, location))
					.request()
					.post(entity);
			throwResponseOddity(response);
			return response;
		}
	
		public <T> Response put(String location, Entity<T> entity) throws ResponseOddityException {
			Response response = client.target(String.format(baseUrl, location))
					.request()
					.put(entity);
			throwResponseOddity(response);
			return response;
		}
	
		public <T> Response putWithHeaders(
				String location,
				Entity<T> entity,
				MultivaluedMap<String, Object> headers
		) throws ResponseOddityException {
	
			Response response = client.target(String.format(baseUrl, location))
					.request()
					.headers(headers)
					.put(entity);
	
			throwResponseOddity(response);
			return response;
		}
	
		@Override
		public void close() {
			client.close();
		}
	}
	