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

package org.unigrid.hedgehog.server.rest;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.ws.rs.core.Response.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.option.RestOptions;
import org.unigrid.hedgehog.server.BaseDefaultNodeTest;

/* A holder of the REST token sends every HTTP method, with and without a small body, to the paths of the ledger, the
   storage and the gridnodes at a node run without --preview. A method that a path takes is answered as left off, and
   any other request is refused by the container before there is a resource. Nothing that a part stands on is built
   for any of them: the resource is made before the filter turns the request away, so a resource that asked for the
   ledger service as it was made would start it. The requests are written out by hand, as a client library does not
   send every method. */
public class PreviewPartDefaultNodeTest extends BaseDefaultNodeTest {
	private static final int LEFT_OFF = Status.SERVICE_UNAVAILABLE.getStatusCode();
	private static final int NOT_ALLOWED = Status.METHOD_NOT_ALLOWED.getStatusCode();
	private static final int NOT_TAKEN = Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode();
	private static final int NOT_FOUND = Status.NOT_FOUND.getStatusCode();
	private static final int PATIENCE_MILLIS = 30_000;
	private static final String GET = "GET";
	private static final String HEAD = "HEAD";
	private static final String OPTIONS = "OPTIONS";
	private static final String JSON = "application/json";
	private static final String STREAM = "application/octet-stream";
	private static final String XML = "application/xml";
	private static final String ADDRESS = "0".repeat(64);
	private static final List<byte[]> BODIES = List.of(new byte[0], new byte[] { 1, 2, 3, 4 });
	private static final SSLContext TLS = trustingEveryCertificate();

	private static final List<String> EVERY_METHOD = List.of(GET, HEAD, "POST", "PUT", "DELETE", "PATCH", OPTIONS,
		"TRACE");

	private static final List<Location> LOCATIONS = List.of(
		new Location("/ledger", "ledger", JSON, List.of(GET)),
		new Location("/ledger/account/" + ADDRESS, "ledger", JSON, List.of(GET)),
		new Location("/ledger/block/1", "ledger", JSON, List.of(GET)),
		new Location("/ledger/transaction", "ledger", JSON, List.of("POST")),
		new Location("/storage", "storage", STREAM, List.of("POST", GET, "DELETE")),
		new Location("/gridnode", "gridnode", JSON, List.of(GET)),
		new Location("/gridnode/collateral", "gridnode", JSON, List.of(GET)),
		new Location("/gridnode/start", "gridnode", JSON, List.of("PUT")),
		new Location("/gridnode/stop", "gridnode", JSON, List.of("PUT"))
	);

	/* A path of a resource, the part it belongs to, a media type that its methods take and the methods it has. The
	   container answers HEAD on a path that has GET, and answers OPTIONS on every path by itself. */
	private record Location(String path, String part, String mediaType, List<String> methods) {
		List<String> taken() {
			return methods.contains(GET) ? Stream.concat(methods.stream(), Stream.of(HEAD)).toList() : methods;
		}

		List<String> refused() {
			return EVERY_METHOD.stream().filter(method -> !taken().contains(method) && !OPTIONS.equals(method))
				.toList();
		}
	}

	private record Request(String method, String path, String mediaType, byte[] body) {
		@Override
		public String toString() {
			return method + " " + path + (body.length > 0 ? " with " + body.length + " bytes of " + mediaType : "");
		}
	}

	private record Reply(int status, String body) { }

	@SneakyThrows
	private static SSLContext trustingEveryCertificate() {
		final SSLContext context = SSLContext.getInstance("TLS");

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
		return context;
	}

	private static byte[] head(final Request request) {
		final StringBuilder head = new StringBuilder(request.method()).append(' ').append(request.path())
			.append(" HTTP/1.1\r\nHost: localhost\r\nAuthorization: ").append(BearerTokenFilter.SCHEME).append(' ')
			.append(RestOptions.getToken()).append("\r\n");

		if (request.body().length > 0) {
			head.append("Content-Type: ").append(request.mediaType()).append("\r\n");
		}

		return head.append("Content-Length: ").append(request.body().length).append("\r\n\r\n").toString()
			.getBytes(StandardCharsets.US_ASCII);
	}

	private static String readLine(final InputStream in) throws IOException {
		final ByteArrayOutputStream line = new ByteArrayOutputStream();

		for (int read = in.read(); read != -1 && read != '\n'; read = in.read()) {
			line.write(read);
		}

		return line.toString(StandardCharsets.US_ASCII).trim();
	}

	/* The status of the answer and the body that it gives the length of, which an answer to HEAD does not carry */
	private static Reply send(final Request request) throws IOException {
		try (SSLSocket socket = (SSLSocket) TLS.getSocketFactory().createSocket(RestOptions.getHost(),
			RestOptions.getPort())) {

			socket.setSoTimeout(PATIENCE_MILLIS);

			final OutputStream out = socket.getOutputStream();

			out.write(head(request));
			out.write(request.body());
			out.flush();

			final InputStream in = socket.getInputStream();
			final int status = Integer.parseInt(readLine(in).split(" ")[1]);
			int length = 0;

			for (String header = readLine(in); !header.isEmpty(); header = readLine(in)) {
				if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
					length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
				}
			}

			return new Reply(status, new String(in.readNBytes(HEAD.equals(request.method()) ? 0 : length),
				StandardCharsets.UTF_8));
		}
	}

	/* The answer to the request, with what of the preview the node built for it added to the problems */
	private Reply ask(final Request request, final List<String> problems) throws IOException {
		final List<Class<?>> before = createdOfThePreview();
		final Reply reply = send(request);
		final List<String> built = createdOfThePreview().stream().filter(type -> !before.contains(type))
			.map(Class::getSimpleName).toList();

		if (!built.isEmpty()) {
			problems.add(request + " built " + built);
		}

		return reply;
	}

	private void askFor(final Request request, final List<Integer> statuses, final List<String> problems)
		throws IOException {

		final Reply reply = ask(request, problems);

		if (!statuses.contains(reply.status())) {
			problems.add(request + " was answered " + reply.status());
		}
	}

	private void askLeftOff(final Request request, final String part, final List<String> problems) throws IOException {
		final Reply reply = ask(request, problems);
		final String text = HEAD.equals(request.method()) ? "" : "This release does not include the " + part + " part";

		if (reply.status() != LEFT_OFF || !text.equals(reply.body())) {
			problems.add(request + " was answered " + reply.status() + " \"" + reply.body() + "\"");
		}
	}

	private void assertNothingOfThePreview(final List<String> problems) {
		assertThat(problems, is(empty()));
		assertThat(createdOfThePreview(), is(empty()));
		assertThat(threadsOfThePreview(), is(empty()));
	}

	@Example
	public void shouldAnswerEveryMethodThatAPathTakesAsLeftOff() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (Location location : LOCATIONS) {
			for (String method : location.taken()) {
				for (byte[] body : BODIES) {
					askLeftOff(new Request(method, location.path(), location.mediaType(), body), location.part(),
						problems);
				}
			}
		}

		assertNothingOfThePreview(problems);
	}

	@Example
	public void shouldRefuseEveryOtherMethodOfAPathAsNotAllowed() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (Location location : LOCATIONS) {
			for (String method : location.refused()) {
				for (byte[] body : BODIES) {
					askFor(new Request(method, location.path(), location.mediaType(), body), List.of(NOT_ALLOWED),
						problems);
				}
			}
		}

		assertNothingOfThePreview(problems);
	}

	/* A method that names what it takes is not matched by a body of another type, and one that names nothing is
	   matched and so left off */
	@Example
	public void shouldBuildNothingForABodyOfATypeThatNoMethodNames() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (Location location : LOCATIONS) {
			for (String method : location.taken()) {
				askFor(new Request(method, location.path(), XML, BODIES.getLast()), List.of(NOT_TAKEN, LEFT_OFF),
					problems);
			}
		}

		assertNothingOfThePreview(problems);
	}

	/* The container answers the request from what it knows of the path, and whatever it answers, it makes no
	   resource for it */
	@Example
	public void shouldBuildNothingForAnOptionsRequest() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (Location location : LOCATIONS) {
			for (byte[] body : BODIES) {
				ask(new Request(OPTIONS, location.path(), location.mediaType(), body), problems);
			}
		}

		assertNothingOfThePreview(problems);
	}

	/* The limit on the body of a request names this prefix as one that takes an upload, and no resource answers on
	   it */
	@Example
	public void shouldAnswerThePathsOfAStorageObjectAsNotThere() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (String path : List.of("/storage-object", "/storage-object/object")) {
			for (String method : EVERY_METHOD) {
				for (byte[] body : BODIES) {
					askFor(new Request(method, path, STREAM, body), List.of(NOT_FOUND), problems);
				}
			}
		}

		assertNothingOfThePreview(problems);
	}
}
