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

package org.unigrid.hedgehog.command.cli;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import mockit.integration.TestRunnerDecorator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.ExitCode;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.network.Node;
import picocli.CommandLine;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class NodeRemoveTest {
	/* How long the node waits for the command to ask, which it does within a moment */
	private static final int PATIENCE_MILLIS = 20_000;

	@Provide
	public Arbitrary<String> addresses() {
		final Arbitrary<Integer> octet = Arbitraries.integers().between(0, 255);

		return Combinators.combine(octet, octet, octet, octet, Arbitraries.integers().between(1, 65535))
			.as("%d.%d.%d.%d:%d"::formatted);
	}

	@Provide
	public Arbitrary<Set<Node>> nodes() {
		return addresses().map(NodeRemoveTest::node).set().ofMaxSize(5);
	}

	@SneakyThrows
	private static Node node(String address) {
		return Node.fromAddress(address);
	}

	@Property(tries = 30)
	public void deletesTheNodeAndPrintsThoseLeft(@ForAll("addresses") String address,
		@ForAll("nodes") Set<Node> left) {

		final NodeRemove command = new NodeRemove();

		new CommandLine(command).parseArgs(address);

		final Result result = RestCommandFixture.run(command, Response.ok(left).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.DELETE, "/node/" + address,
			Optional.empty(), Optional.empty()))));

		assertThat(result.out().lines().toList(), equalTo(List.of(left.toString())));
		assertThat(result.err(), equalTo(""));
	}

	/* The node that the command talks to, on this machine and without TLS, which the real one has */
	private static void answerOnce(ServerSocket node, String status) {
		try (Socket client = node.accept()) {
			final BufferedReader request = new BufferedReader(
				new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));

			while (!request.readLine().isEmpty()) {
				continue;
			}

			client.getOutputStream().write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\n"
				+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		} catch (IOException ex) {
			return;
		}
	}

	/* NodeResource.remove answers a removal with a status and nothing more: no entity, and so no content type. The
	   fakes of the other examples stand in for the client and the answers until they are cleared, and are cleared
	   again for the examples after this one. */
	@SneakyThrows
	private static HedgehogCli.Result removeFromANodeThatAnswers(String status) {
		TestRunnerDecorator.cleanUpAllMocks();

		try (ServerSocket node = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			final Thread answering = new Thread(() -> answerOnce(node, status));

			node.setSoTimeout(PATIENCE_MILLIS);

			new MockUp<RestClientCommand>() {
				@Mock public /* static */ RestClient connect() {
					return new RestClient("localhost", node.getLocalPort(), false, "token");
				}
			};

			/* The first client in a run starts the logging of Netty, which says so on standard output */
			new RestClient("localhost", node.getLocalPort(), false, "token").close();
			answering.start();

			final HedgehogCli.Result result = HedgehogCli.run(() -> new CommandLine(new NodeRemove()),
				"10.0.0.1:52883");

			answering.join();
			return result;
		} finally {
			TestRunnerDecorator.cleanUpAllMocks();
		}
	}

	@Example
	public void confirmsARemovalThatTheNodeAnswersWithoutAList() {
		final HedgehogCli.Result result = removeFromANodeThatAnswers("200 OK");

		assertThat(result.err(), equalTo(""));
		assertThat(result.out().lines().toList(), equalTo(List.of("OK")));
		assertThat(result.exitCode(), equalTo(ExitCode.OK));
	}

	@Example
	public void saysSoWhenTheNodeDoesNotKnowTheNodeToRemove() {
		final HedgehogCli.Result result = removeFromANodeThatAnswers("404 Not Found");

		assertThat(result.err(), equalTo(""));
		assertThat(result.out().lines().toList(), equalTo(List.of("Not Found")));
		assertThat(result.exitCode(), equalTo(ExitCode.FAILED));
	}

	@Example
	public void requiresAnAddress() {
		assertThrows(CommandLine.MissingParameterException.class,
			() -> new CommandLine(new NodeRemove()).parseArgs());
	}
}
