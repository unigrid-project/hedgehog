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
import jakarta.ws.rs.core.Response.Status.Family;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.model.util.TerminalText;
import org.unigrid.hedgehog.server.rest.RestToken;
import picocli.CommandLine.IExitCodeGenerator;

/* The command exits with FAILED when the node refused it, could not be reached or answered with anything but a
   success, so that a script can tell */
@RequiredArgsConstructor
public class RestClientCommand implements Runnable, IExitCodeGenerator {
	private final String method;
	@Getter @Setter(AccessLevel.PROTECTED) private int exitCode = ExitCode.OK;
	@Getter(AccessLevel.PROTECTED) private String location;
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
				conclude(response);
			}
		}

		private void delete(RestClient rest) throws ResponseOddityException {
			conclude(headers.isPresent()
				? rest.deleteWithHeaders(getLocation(), headers.get()) : rest.delete(getLocation()));
		}

		private void post(RestClient rest) throws ResponseOddityException {
			conclude(RestClientCommand.this.post(rest));
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

				exitCode = ExitCode.FAILED;
			} else {
				conclude(response);
			}
		}
	}

	/* A status that is no success is a refusal, whatever the command made of the answer */
	private void conclude(Response response) {
		execute(response);

		if (exitCodeOf(response) != ExitCode.OK) {
			exitCode = ExitCode.FAILED;
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
		try (RestClient rest = connect()) {
			dispatch(rest);
		} catch (ResponseOddityException ex) {
			System.err.println(refusal(ex));
			exitCode = ExitCode.FAILED;
		} catch (IOException ex) {
			System.err.println(describe(ex));
			exitCode = ExitCode.FAILED;
		} catch (LocalFileException ex) {
			System.err.println(ex.getMessage());
			exitCode = ExitCode.UNUSABLE;
		}
	}

	public static int exitCodeOf(Response response) {
		return response.getStatusInfo().getFamily() == Family.SUCCESSFUL ? ExitCode.OK : ExitCode.FAILED;
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

		return TerminalText.printable(String.valueOf(ex.getMessage()));
	}

	/* What failed with a file on this machine, named as the user gave it, as the exception names a file of its own */
	public static String describe(String operation, Path path, IOException ex) {
		final String reason;

		if (ex instanceof NoSuchFileException) {
			reason = "no such file or directory";
		} else if (ex instanceof AccessDeniedException) {
			reason = "permission denied";
		} else if (ex instanceof FileSystemException other) {
			reason = Optional.ofNullable(other.getReason()).orElse(other.getClass().getSimpleName());
		} else {
			reason = String.valueOf(ex.getMessage());
		}

		return TerminalText.printable(operation + " " + path + ": " + reason);
	}

	public void setHeaders(MultivaluedMap<String, Object> headers) {
		this.headers = Optional.of(headers);
	}

	/* The line printed when the node answers with a status that the client takes for no answer at all */
	protected String refusal(ResponseOddityException oddity) {
		return describe(oddity);
	}

	protected Response post(final RestClient rest) throws ResponseOddityException {
		return rest.post(getLocation(), getEntity());
	}

	protected <T> Entity<T> getEntity() {
		throw new UnsupportedOperationException();
	}

	protected void execute(Response response) {
		throw new UnsupportedOperationException();
	}

	/* A file on this machine that the command was given and cannot use, which ends the command with UNUSABLE and is
	   not taken for a token that cannot be read */
	protected static final class LocalFileException extends RuntimeException {
		public LocalFileException(String operation, Path path, IOException cause) {
			super(describe(operation, path, cause), cause);
		}
	}
}
