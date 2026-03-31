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

	package org.unigrid.hedgehog.command.util;

	import jakarta.ws.rs.client.Client;
	import jakarta.ws.rs.client.ClientBuilder;
	import jakarta.ws.rs.client.Entity;
	import jakarta.ws.rs.client.Invocation;
	import jakarta.ws.rs.core.MultivaluedMap;
	import jakarta.ws.rs.core.Response;
	
	public abstract class RestClientCommand {
	
		protected final String httpMethod;
		protected final String pathTemplate;
	
		private MultivaluedMap<String, Object> headers;
	
		protected RestClientCommand(String httpMethod, String pathTemplate) {
			this.httpMethod = httpMethod;
			this.pathTemplate = pathTemplate;
		}
	
		/** MÅSTE implementeras av anonyma klasser */
		protected abstract String getLocation();
	
		/** Override vid PUT/POST */
		protected Entity<?> getEntity() {
			return null;
		}
	
		/** Override för resultathantering */
		protected abstract void execute(Response response);
	
		public void setHeaders(MultivaluedMap<String, Object> headers) {
			this.headers = headers;
		}
	
		public final void run() {
			Client client = ClientBuilder.newClient();
	
			Invocation.Builder builder = client
					.target("http://localhost:8080")
					.path(getLocation())
					.request();
	
			if (headers != null) {
				builder.headers(headers);
			}
	
			Response response;
			Entity<?> entity = getEntity();
	
			if (entity != null) {
				response = builder.method(httpMethod, entity);
			} else {
				response = builder.method(httpMethod);
			}
	
			execute(response);
	
			response.close();
			client.close();
		}
	}
	