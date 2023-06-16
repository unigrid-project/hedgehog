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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.client.ResponseOddityException;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;

@RequiredArgsConstructor
public class RestClientCommand implements Runnable {
	private final String method;
	private String location;
	private Optional<Supplier<?>> defaultSupplier = Optional.empty();
	private Optional<MultivaluedMap<String, Object>> headers = Optional.empty();

	public RestClientCommand(String method, String location) {
		this(method);
		this.location = location;
	}

	public RestClientCommand(String method, String location, Optional<Supplier<?>> defaultSupplier) {
		this(method, location);
		this.defaultSupplier = defaultSupplier;
	}

	private class MethodCallback {
		private void get(RestClient rest) throws ResponseOddityException {
			final Response response = rest.get(getLocation());

			if (Status.fromStatusCode(response.getStatus()) == Status.NO_CONTENT) {
				defaultSupplier.ifPresentOrElse(s -> {
					System.out.println(s.get());
				}, () -> {
					System.out.println(response.getStatusInfo());
				});
			} else {
				execute(response);
			}
		}

		private void delete(RestClient rest) throws ResponseOddityException {
			execute(rest.delete(getLocation()));
		}

		private void post(RestClient rest) throws ResponseOddityException {
			execute(rest.post(getLocation(), getEntity()));
		}

		private void put(RestClient rest) throws ResponseOddityException {
			Response response;

			if (headers.isPresent()) {
				response = rest.putWithHeaders(getLocation(), getEntity(), headers.get());
			} else {
				response = rest.put(getLocation(), getEntity());
			}

			if (Status.fromStatusCode(response.getStatus()) == Status.UNAUTHORIZED) {
				defaultSupplier.ifPresentOrElse(s -> {
					System.out.println(s.get());
				}, () -> {
					System.out.println(response.getStatusInfo());
				});
			} else {
				execute(response);
			}
		}
	}

	@Override
	public void run() {
		try (RestClient rest = new RestClient(RestOptions.getHost(), RestOptions.getPort(), true)) {
			final MethodCallback callback = new MethodCallback();

			switch (method) {
				case HttpMethod.GET -> callback.get(rest);
				case HttpMethod.POST -> callback.post(rest);
				case HttpMethod.PUT -> callback.put(rest);
				case HttpMethod.DELETE -> callback.delete(rest);
				default -> throw new UnsupportedOperationException();
			}
		} catch (ResponseOddityException ex) {
			System.err.println(ex.getMessage());
		}
	}

	public void setHeaders(MultivaluedMap<String, Object> headers) {
		this.headers = Optional.of(headers);
	}

	protected <T> Entity<T> getEntity() {
		throw new UnsupportedOperationException();
	}

	protected String getLocation() {
		return location;
	}

	protected void execute(Response response) {
		throw new UnsupportedOperationException();
	}
}
