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
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;
import jakarta.ws.rs.core.Response.StatusType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.integration.TestRunnerDecorator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import org.unigrid.hedgehog.client.rest.ResponseOddityException;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Answer;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Connection;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.jqwik.RestoreOptionsHook;
import org.unigrid.hedgehog.model.option.RestOptions;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class RestClientCommandTest {
	private static final List<String> METHODS = List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
		HttpMethod.DELETE);

	/* How long the node waits for the command to ask, which it does within a moment */
	private static final int PATIENCE_MILLIS = 20_000;

	/* What a node announces of a body and does not send */
	private static final String MISSING = " ".repeat(64);

	private static final Entity<String> BODY = Entity.text("body");
	private static final String HEADER = "X-Header";
	private static final Connection CONNECTION = new Connection(RestCommandFixture.HOST, RestCommandFixture.PORT,
		true, RestCommandFixture.TOKEN);

	private static RestClientCommand command(String method, String location, Optional<String> fallback) {
		return new RestClientCommand(method, location, fallback.<Supplier<?>>map(text -> () -> text)) {
			@Override
			@SuppressWarnings("unchecked")
			protected <T> Entity<T> getEntity() {
				return (Entity<T>) BODY;
			}

			@Override
			protected void execute(Response response) {
				System.out.println(executed(response.getStatus()));
			}
		};
	}

	private static String executed(int status) {
		return "Executed " + status;
	}

	private static boolean carriesAnEntity(String method) {
		return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method);
	}

	@Provide
	public Arbitrary<String> methods() {
		return Arbitraries.of(METHODS);
	}

	@Property(tries = 50)
	public void sendsTheRequestItDescribesOverASecureConnection(@ForAll("methods") String method,
		@ForAll @AlphaChars String path, @ForAll Optional<@AlphaChars String> header) {

		final RestClientCommand command = command(method, "/" + path, Optional.empty());
		final Optional<MultivaluedMap<String, Object>> headers = header.map(value -> new MultivaluedHashMap<>(
			Map.of(HEADER, value)));

		headers.ifPresent(command::setHeaders);

		final Result result = RestCommandFixture.run(command, Response.ok().build());
		final Optional<Entity<?>> entity = carriesAnEntity(method) ? Optional.of(BODY) : Optional.empty();
		final boolean postIgnoresHeaders = HttpMethod.POST.equals(method);

		assertThat(result.connection(), equalTo(Optional.of(CONNECTION)));
		assertThat(result.closed(), is(true));
		assertThat(result.request(), equalTo(Optional.of(new Request(method, "/" + path, entity,
			postIgnoresHeaders ? Optional.empty() : headers))));
	}

	@Property(tries = 50)
	public void handsEveryAnswerWithContentToTheCommand(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status, @ForAll Optional<@AlphaChars String> fallback) {

		Assume.that(!(HttpMethod.GET.equals(method) && status == Status.NO_CONTENT.getStatusCode()));
		Assume.that(!(HttpMethod.PUT.equals(method) && status == Status.UNAUTHORIZED.getStatusCode()));

		final Result result = RestCommandFixture.run(command(method, "/", fallback),
			Response.status(status).build());

		assertThat(result.out().lines().toList(), equalTo(List.of(executed(status))));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 20)
	public void printsTheFallbackOrStatusWhenNothingCameBack(@ForAll boolean put,
		@ForAll Optional<@AlphaChars String> fallback) {

		final Status status = put ? Status.UNAUTHORIZED : Status.NO_CONTENT;
		final RestClientCommand command = command(put ? HttpMethod.PUT : HttpMethod.GET, "/", fallback);
		final Result result = RestCommandFixture.run(command, Response.status(status).build());

		assertThat(result.out().lines().toList(), equalTo(List.of(fallback.orElse(status.getReasonPhrase()))));
		assertThat(result.err(), equalTo(""));
	}

	@Property(tries = 20)
	public void refusesAnUnsupportedMethod(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String method) {
		Assume.that(!METHODS.contains(method));
		assertThrows(UnsupportedOperationException.class, () -> RestCommandFixture.run(command(method, "/",
			Optional.empty()), Response.ok().build()));
	}

	@Property(tries = 20)
	public void cannotSendOrHandleAnythingUntilTaughtTo(@ForAll("methods") String method) {
		assertThrows(UnsupportedOperationException.class, () -> RestCommandFixture.run(new RestClientCommand(method,
			"/"), Response.ok().build()));
	}

	@Property(tries = 50)
	public void reportsAnOddAnswerOnStandardError(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status) {

		final ResponseOddityException oddity = new ResponseOddityException(Response.status(status).build()
			.getStatusInfo());

		final Result result = RestCommandFixture.runWithOddity(command(method, "/", Optional.empty()), oddity);

		assertThat(result.closed(), is(true));
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(oddity.getMessage())));
	}

	/* The node says in the body of the answer why it turned a request down, which the status line does not say */
	@Property(tries = 50)
	public void addsTheReasonOfTheNodeToTheOddAnswer(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status,
		@ForAll @AlphaChars @StringLength(min = 1, max = 40) String reason, @ForAll boolean endsWithALineBreak) {

		final StatusType type = Response.status(status).build().getStatusInfo();
		final ResponseOddityException oddity = new ResponseOddityException(type,
			reason + (endsWithALineBreak ? "\n" : ""));

		final Result result = RestCommandFixture.runWithOddity(command(method, "/", Optional.empty()), oddity);

		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(new ResponseOddityException(type).getMessage() + ": "
			+ reason)));
	}

	/* As the shell sees it: through the command line of the application, which shows the trace of an exception that
	   the command lets escape */
	private static HedgehogCli.Result cli(Answer node, List<String> arguments) {
		final AtomicReference<HedgehogCli.Result> cli = new AtomicReference<>();
		final String[] line = Stream.concat(Stream.of("cli"), arguments.stream()).toArray(String[]::new);

		RestCommandFixture.run(() -> cli.set(HedgehogCli.run(line)), node);
		return cli.get();
	}

	@Provide
	public Arbitrary<List<String>> commands() {
		return Arbitraries.of(List.of("node-list"), List.of("stop"), List.of("node-add", "10.0.0.1:52883"),
			List.of("node-remove", "10.0.0.1:52883"));
	}

	/* A node that does not answer, or answers too late, is reported by the client as an exception of its own around
	   the socket error: the operator is told in a line what could not be reached, and not shown the trace */
	@Property(tries = 10)
	public void tellsInOneLineThatTheNodeCannotBeReached(@ForAll("commands") List<String> command,
		@ForAll @AlphaChars @StringLength(min = 1, max = 20) String reason, @ForAll boolean silent) {

		final IOException cause = silent ? new SocketTimeoutException(reason) : new ConnectException(reason);
		final HedgehogCli.Result result = cli(RestCommandFixture.unreachable(cause), command);

		result.assertNoStackTrace();
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of("Could not reach the node at "
			+ RestCommandFixture.HOST + ":" + RestCommandFixture.PORT + ": " + cause.getClass().getSimpleName() + ": "
			+ reason)));

		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
	}

	private static String answerWith(String contentType, String body) {
		return "HTTP/1.1 200 OK\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s"
			.formatted(contentType, body.getBytes(StandardCharsets.UTF_8).length, body);
	}

	private static void answerOnce(ServerSocket node, String answer) {
		try (Socket client = node.accept()) {
			final BufferedReader request = new BufferedReader(
				new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));

			while (!request.readLine().isEmpty()) {
				continue;
			}

			client.getOutputStream().write(answer.getBytes(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			return;
		}
	}

	/* The node that the command talks to, on this machine and without TLS, which the real one has. It sends what it
	   is given to the first request. The fakes of the other examples stand in for the client and the answers until
	   they are cleared, and are cleared again for the examples after this one. */
	@SneakyThrows
	private static HedgehogCli.Result askANodeThatSends(String answer, String... command) {
		TestRunnerDecorator.cleanUpAllMocks();

		try (ServerSocket node = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			final Thread answering = new Thread(() -> answerOnce(node, answer));

			node.setSoTimeout(PATIENCE_MILLIS);

			new MockUp<RestClientCommand>() {
				@Mock public /* static */ RestClient connect() {
					return new RestClient("localhost", node.getLocalPort(), false, "token");
				}
			};

			/* The first client in a run starts the logging of Netty, which says so on standard output */
			new RestClient("localhost", node.getLocalPort(), false, "token").close();
			answering.start();

			final HedgehogCli.Result result = HedgehogCli.run(command);

			answering.join();
			return result;
		} finally {
			TestRunnerDecorator.cleanUpAllMocks();
		}
	}

	/* The answer of a node whose connection ends before the body that it announced is whole */
	private static String answerThatBreaksOffAfter(String sent) {
		final String whole = answerWith("application/json", sent + MISSING);

		return whole.substring(0, whole.length() - MISSING.length());
	}

	/* The node answered, so it was reached: what it sent is what the command cannot make anything of, whether the
	   body is no list of nodes or no one here knows how to read its type */
	@Example
	public void saysThatTheAnswerCannotBeReadAndNotThatTheNodeCannotBeReached() {
		final List<List<String>> unreadable = List.of(List.of("application/json", "\"text\""),
			List.of("application/json", "{broken"), List.of("text/plain", "hello"));

		for (List<String> sent : unreadable) {
			final HedgehogCli.Result result = askANodeThatSends(answerWith(sent.get(0), sent.get(1)), "cli",
				"node-list");

			final String shown = sent + " was shown as " + result.err();

			result.assertNoStackTrace();
			assertThat(shown, result.out(), equalTo(""));
			assertThat(shown, result.err(), startsWith("The node answered something this command cannot read: "));
			assertThat(shown, result.exitCode(), equalTo(ExitCode.FAILED));
		}
	}

	/* Nor was a node out of reach whose connection ended inside the body that it announced, in the middle of a node of
	   the list or between two of them, though the client reports that around the error of the socket, as it reports a
	   node that does not answer */
	@Example
	public void saysThatTheAnswerCannotBeReadWhenItBreaksOffInsideTheBody() {
		for (String sent : List.of("[{", "[    ")) {
			final HedgehogCli.Result result = askANodeThatSends(answerThatBreaksOffAfter(sent), "cli", "node-list");
			final String shown = "A body that breaks off after " + sent + " was shown as " + result.err();

			result.assertNoStackTrace();
			assertThat(shown, result.out(), equalTo(""));
			assertThat(shown, result.err().lines().toList(), hasSize(1));
			assertThat(shown, result.err(), startsWith("The node answered something this command cannot read: "));
			assertThat(shown, result.exitCode(), equalTo(ExitCode.FAILED));
		}
	}

	/* The node at the address that the command line names, asked by the client itself: the fakes of the other examples
	   are cleared and none takes their place, and the token is given, so that none is read from a file. No request
	   leaves this machine for an address that none can be made of. */
	private static HedgehogCli.Result askTheNodeAt(String host, int port) {
		TestRunnerDecorator.cleanUpAllMocks();

		/* The first client in a run starts the logging of Netty, which says so on standard output */
		new RestClient(RestCommandFixture.HOST, RestCommandFixture.PORT, false, RestCommandFixture.TOKEN).close();

		return HedgehogCli.run("cli", "--resthost", host, "--restport", String.valueOf(port), "--resttoken",
			RestCommandFixture.TOKEN, "node-list");
	}

	/* An address that no request can be made of names no node, so none was asked and none answered: a host that is a
	   bare IPv6 address, whose last group the client takes for the port, and a port that no host has. The client
	   reports both as it reports a node that does not answer, around an error that is none of a socket. */
	@Example
	@AddLifecycleHook(RestoreOptionsHook.class)
	public void saysThatTheNodeCannotBeReachedWhenNoRequestCanBeMadeOfItsAddress() {
		final List<Map.Entry<String, Integer>> unusable = List.of(Map.entry("::1", RestOptions.DEFAULT_PORT),
			Map.entry(RestCommandFixture.HOST, 70_000));

		for (Map.Entry<String, Integer> address : unusable) {
			final HedgehogCli.Result result = askTheNodeAt(address.getKey(), address.getValue());
			final String shown = address + " was shown as " + result.err();

			result.assertNoStackTrace();
			assertThat(shown, result.out(), equalTo(""));
			assertThat(shown, result.err().lines().toList(), hasSize(1));
			assertThat(shown, result.err(), startsWith("Could not reach the node at " + address.getKey() + ":"
				+ address.getValue() + ": "));

			assertThat(shown, result.exitCode(), equalTo(ExitCode.FAILED));
		}
	}

	@Property(tries = 20)
	public void pointsAtTheMissingTokenFile(@ForAll("methods") String method, @ForAll @AlphaChars String file) {
		final Result result = RestCommandFixture.runWithoutToken(command(method, "/", Optional.empty()),
			new NoSuchFileException("/" + file));

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of("No REST token in /" + file
			+ "; is the daemon running? Otherwise pass --resttoken.")));
	}

	@Property(tries = 20)
	public void reportsATokenThatCannotBeRead(@ForAll("methods") String method, @ForAll @AlphaChars String problem) {

		final Result result = RestCommandFixture.runWithoutToken(command(method, "/", Optional.empty()),
			new IOException(problem));

		assertThat(result.connection(), equalTo(Optional.empty()));
		assertThat(result.out(), equalTo(""));
		assertThat(result.err().lines().toList(), equalTo(List.of(problem)));
	}

	/* A status that is no success is a refusal, whatever the command made of the answer */
	@Property(tries = 50)
	public void exitsWithFailedUnlessTheNodeAnsweredWithASuccess(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status) {

		Assume.that(!(HttpMethod.GET.equals(method) && status == Status.NO_CONTENT.getStatusCode()));
		Assume.that(!(HttpMethod.PUT.equals(method) && status == Status.UNAUTHORIZED.getStatusCode()));

		final RestClientCommand command = command(method, "/", Optional.empty());

		RestCommandFixture.run(command, Response.status(status).build());
		assertThat(command.getExitCode(), equalTo(Family.familyOf(status) == Family.SUCCESSFUL ? ExitCode.OK
			: ExitCode.FAILED));
	}

	@Property(tries = 20)
	public void exitsWithOkWhenNothingCameBackAndWithFailedWhenThePutWasRefused(@ForAll boolean put,
		@ForAll Optional<@AlphaChars String> fallback) {

		final RestClientCommand command = command(put ? HttpMethod.PUT : HttpMethod.GET, "/", fallback);

		RestCommandFixture.run(command, Response.status(put ? Status.UNAUTHORIZED : Status.NO_CONTENT).build());
		assertThat(command.getExitCode(), equalTo(put ? ExitCode.FAILED : ExitCode.OK));
	}

	@Property(tries = 20)
	public void exitsWithFailedOnAnOddAnswer(@ForAll("methods") String method,
		@ForAll @IntRange(min = 100, max = 599) int status) {

		final RestClientCommand command = command(method, "/", Optional.empty());

		RestCommandFixture.runWithOddity(command, new ResponseOddityException(Response.status(status).build()
			.getStatusInfo()));

		assertThat(command.getExitCode(), equalTo(ExitCode.FAILED));
	}

	@Property(tries = 20)
	public void exitsWithFailedWithoutAToken(@ForAll("methods") String method, @ForAll boolean missing) {
		final RestClientCommand command = command(method, "/", Optional.empty());

		RestCommandFixture.runWithoutToken(command, missing ? new NoSuchFileException("/token")
			: new IOException("unreadable"));

		assertThat(command.getExitCode(), equalTo(ExitCode.FAILED));
	}

	@Example
	public void takesOnlyAnAddressOfThisMachineForThisMachine() {
		assertThat(RestClientCommand.isLoopback("localhost"), is(true));
		assertThat(RestClientCommand.isLoopback("::1"), is(true));
		assertThat(RestClientCommand.isLoopback("203.0.113.9"), is(false));
		assertThat(RestClientCommand.isLoopback("unresolvable.invalid"), is(false));
	}
}
