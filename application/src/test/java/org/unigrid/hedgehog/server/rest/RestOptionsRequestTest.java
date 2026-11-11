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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
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

/* An OPTIONS request asks what a path takes. No resource of the node has a method for it, so the container answers
   it by itself, and it names the methods of the path and nothing more: the node hands out no description of its
   resources, whether a request asks for one or not. The token is asked for before any of it, as it is for every
   other request. The requests are written out by hand, as the client of the node sends no OPTIONS request. */
public class RestOptionsRequestTest extends BaseDefaultNodeTest {
	private static final int OK = Status.OK.getStatusCode();
	private static final int UNAUTHORIZED = Status.UNAUTHORIZED.getStatusCode();
	private static final int PATIENCE_MILLIS = 30_000;
	private static final String GET = "GET";
	private static final String HEAD = "HEAD";
	private static final String OPTIONS = "OPTIONS";
	private static final String POST = "POST";
	private static final String ALLOW = "Allow";
	private static final String CHALLENGE = "WWW-Authenticate";
	private static final String LENGTH = "Content-Length";
	private static final String TYPE = "Content-Type";
	private static final String PLAIN_TEXT = "text/plain";
	private static final String DESCRIPTION = "/application.wadl";
	private static final String TYPE_OF_A_DESCRIPTION = "application/vnd.sun.wadl+xml";
	private static final SSLContext TLS = trustingEveryCertificate();

	private static final List<Location> OF_THIS_RELEASE = List.of(
		new Location("/version", List.of(GET)),
		new Location("/status", List.of(GET)),
		new Location("/height", List.of(GET)),
		new Location("/node", List.of(GET, POST)),
		new Location("/gridspork", List.of(GET)),
		new Location("/gridspork/mint-supply", List.of(GET)),
		new Location("/gridspork/board-keys", List.of(GET)),
		new Location("/gridspork/wire", List.of(POST)),
		new Location("/bootstrap", List.of(GET))
	);

	/* A path of this release and the methods that its resource has. The container answers HEAD on a path that has
	   GET, and OPTIONS on every path. */
	private record Location(String path, List<String> methods) {
		Set<String> allowed() {
			return Stream.concat(methods.stream(), methods.contains(GET) ? Stream.of(HEAD, OPTIONS)
				: Stream.of(OPTIONS)).collect(Collectors.toCollection(TreeSet::new));
		}
	}

	/* The headers are looked up by name whatever the case the node wrote them in */
	private record Reply(int status, Map<String, String> headers, String body) {
		String header(final String name) {
			return headers.getOrDefault(name, "");
		}
	}

	@SneakyThrows
	private static SSLContext trustingEveryCertificate() {
		final SSLContext context = SSLContext.getInstance("TLS");

		context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
		return context;
	}

	/* The methods that a list of them names, as the header and the body of an answer both give one */
	private static Set<String> methodsOf(final String list) {
		return Stream.of(list.split(",")).map(String::trim).filter(method -> !method.isEmpty())
			.collect(Collectors.toCollection(TreeSet::new));
	}

	private static String readLine(final InputStream in) throws IOException {
		final ByteArrayOutputStream line = new ByteArrayOutputStream();

		for (int read = in.read(); read != -1 && read != '\n'; read = in.read()) {
			line.write(read);
		}

		return line.toString(StandardCharsets.US_ASCII).trim();
	}

	private static byte[] head(final String method, final String path, final List<String> headers) {
		final StringBuilder head = new StringBuilder(method).append(' ').append(path)
			.append(" HTTP/1.1\r\nHost: localhost\r\n");

		headers.forEach(header -> head.append(header).append("\r\n"));
		return head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII);
	}

	/* The answer with the body that it gives the length of, which an answer to HEAD does not carry */
	private static Reply send(final String method, final String path, final List<String> headers) throws IOException {
		try (SSLSocket socket = (SSLSocket) TLS.getSocketFactory().createSocket(RestOptions.getHost(),
			RestOptions.getPort())) {

			socket.setSoTimeout(PATIENCE_MILLIS);
			socket.getOutputStream().write(head(method, path, headers));

			final InputStream in = socket.getInputStream();
			final int status = Integer.parseInt(readLine(in).split(" ")[1]);
			final Map<String, String> answered = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

			for (String header = readLine(in); !header.isEmpty(); header = readLine(in)) {
				final int colon = header.indexOf(':');

				answered.put(header.substring(0, colon), header.substring(colon + 1).trim());
			}

			final int length = HEAD.equals(method) ? 0 : Integer.parseInt(answered.getOrDefault(LENGTH, "0"));

			return new Reply(status, answered, new String(in.readNBytes(length), StandardCharsets.UTF_8));
		}
	}

	private static List<String> withTheToken(final String... headers) {
		return Stream.concat(Stream.of("Authorization: " + BearerTokenFilter.SCHEME + " " + RestOptions.getToken()),
			Stream.of(headers)).toList();
	}

	private static String told(final String request, final Reply reply) {
		return request + " was answered " + reply.status() + " " + reply.headers() + " \"" + reply.body() + "\"";
	}

	private static boolean namesTheMethods(final Reply reply, final Location location) {
		return reply.status() == OK && location.allowed().equals(methodsOf(reply.header(ALLOW)));
	}

	private static boolean asksForTheToken(final Reply reply) {
		return reply.status() == UNAUTHORIZED && BearerTokenFilter.SCHEME.equals(reply.header(CHALLENGE))
			&& reply.header(ALLOW).isEmpty() && reply.body().isEmpty();
	}

	/* The answer is plain text, and its body names the methods as its header does */
	@Example
	public void shouldAnswerAnOptionsRequestWithTheMethodsOfThePath() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (Location location : OF_THIS_RELEASE) {
			final Reply reply = send(OPTIONS, location.path(), withTheToken());

			if (!namesTheMethods(reply, location) || !reply.header(TYPE).startsWith(PLAIN_TEXT)
				|| !location.allowed().equals(methodsOf(reply.body()))) {

				problems.add(told(OPTIONS + " " + location.path(), reply));
			}
		}

		assertThat(problems, is(empty()));
	}

	/* The container can describe a path to a request that asks for a description of it. The node has it describe
	   none, so such a request is told the methods of the path and is sent no body. */
	@Example
	public void shouldDescribeNoPathToARequestThatAsksForADescription() throws IOException {
		final List<String> problems = new ArrayList<>();

		for (Location location : OF_THIS_RELEASE) {
			final Reply reply = send(OPTIONS, location.path(), withTheToken("Accept: " + TYPE_OF_A_DESCRIPTION));

			if (!namesTheMethods(reply, location) || !reply.body().isEmpty()) {
				problems.add(told(OPTIONS + " " + location.path() + " for a description", reply));
			}
		}

		assertThat(problems, is(empty()));
	}

	/* A request without the token is refused before the container looks for the path, so it learns neither the
	   methods of a path nor whether there is one */
	@Example
	public void shouldAskForTheTokenBeforeItAnswers() throws IOException {
		final List<String> problems = new ArrayList<>();
		final Stream<String> paths = Stream.concat(OF_THIS_RELEASE.stream().map(Location::path), Stream.of(DESCRIPTION));

		for (String path : paths.toList()) {
			for (String method : List.of(OPTIONS, HEAD, GET)) {
				final Reply reply = send(method, path, List.of());

				if (!asksForTheToken(reply)) {
					problems.add(told(method + " " + path + " without the token", reply));
				}
			}
		}

		assertThat(problems, is(empty()));
	}
}
