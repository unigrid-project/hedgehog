/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

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
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.server.rest.RestToken;

@RequiredArgsConstructor
public class RestClientCommand implements Runnable {
	/* The private key of a network key travels in this header, to a client that trusts any certificate */
	private static final String BOARD_KEY_HEADER = "privateKey";

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

	private final class MethodCallback {
		private void get(RestClient rest) throws ResponseOddityException {
			final Response response = headers.isPresent()
				? rest.getWithHeaders(getLocation(), headers.get()) : rest.get(getLocation());

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
			execute(headers.isPresent()
				? rest.deleteWithHeaders(getLocation(), headers.get()) : rest.delete(getLocation()));
		}

		private void post(RestClient rest) throws ResponseOddityException {
			execute(RestClientCommand.this.post(rest));
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

	private void dispatch(RestClient rest) throws ResponseOddityException {
		final MethodCallback callback = new MethodCallback();

		switch (method) {
			case HttpMethod.GET -> callback.get(rest);
			case HttpMethod.POST -> callback.post(rest);
			case HttpMethod.PUT -> callback.put(rest);
			case HttpMethod.DELETE -> callback.delete(rest);
			default -> throw new UnsupportedOperationException();
		}
	}

	@Override
	public void run() {
		if (carriesBoardKey() && !isLoopback(RestOptions.getHost())) {
			System.err.println("Refusing to send a private key to " + RestOptions.getHost() + ": the client does not "
				+ "check who answers, so the key could be taken. Run the command on the machine of the node.");
			return;
		}

		try (RestClient rest = connect()) {
			dispatch(rest);
		} catch (ResponseOddityException | IOException ex) {
			System.err.println(describe(ex));
		}
	}

	private boolean carriesBoardKey() {
		return headers.map(sent -> sent.containsKey(BOARD_KEY_HEADER)).orElse(false);
	}

	/* A name that cannot be resolved is not known to be on this machine. Nothing named is this machine. */
	public static boolean isLoopback(String host) {
		try {
			return InetAddress.getByName(host).isLoopbackAddress();
		} catch (UnknownHostException ex) {
			return false;
		}
	}

	/* The node that the options name, with the token that the user passed or the daemon left behind */
	public static RestClient connect() throws IOException {
		return new RestClient(RestOptions.getHost(), RestOptions.getPort(), true, RestToken.resolve());
	}

	public static String describe(Exception ex) {
		if (ex instanceof NoSuchFileException missing) {
			return "No REST token in %s; is the daemon running? Otherwise pass --resttoken."
				.formatted(missing.getFile());
		}

		return ex.getMessage();
	}

	public void setHeaders(MultivaluedMap<String, Object> headers) {
		this.headers = Optional.of(headers);
	}

	protected Response post(final RestClient rest) throws ResponseOddityException {
		return rest.post(getLocation(), getEntity());
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
