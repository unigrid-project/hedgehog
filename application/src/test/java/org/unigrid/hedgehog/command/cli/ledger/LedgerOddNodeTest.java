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

package org.unigrid.hedgehog.command.cli.ledger;

import io.netty.util.internal.logging.InternalLoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.client.rest.RestClient;
import org.unigrid.hedgehog.command.HedgehogCli;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.command.util.RestClientCommand;
import org.unigrid.hedgehog.jqwik.MockitHook;

/* A node that behaves in a way no honest one does, over a real connection: one that takes the connection and says
   nothing must end the command with a line, and not hold it for ever, and one that answers with something that is no
   answer must not be taken for one that cannot be reached. The time a command waits is shortened by the client the
   command opens, which is given a shorter one. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LedgerOddNodeTest {
	private static final Duration PATIENCE = Duration.ofMillis(500);
	private static final Duration LONG_ENOUGH = Duration.ofSeconds(30);

	static {
		/* Logback reports its own start-up on stdout, and Netty brings it up when the first connection is made,
		   which must not end up in the output of a command */
		LoggerFactory.getILoggerFactory();
		InternalLoggerFactory.getInstance(LedgerOddNodeTest.class);
	}

	private static void useNodeAt(final int port, final boolean secure) {
		new MockUp<RestOptions>() {
			@Mock public String getHost() {
				return "localhost";
			}

			@Mock public int getPort() {
				return port;
			}
		};

		new MockUp<RestClientCommand>() {
			@Mock public RestClient connect() {
				return new RestClient("localhost", port, secure, "token", PATIENCE);
			}
		};
	}

	private static HedgehogCli.Result runInfo() throws Exception {
		final ExecutorService caller = Executors.newSingleThreadExecutor();

		try {
			final Future<HedgehogCli.Result> ended = caller.submit(() -> HedgehogCli.run("cli", "ledger", "info"));

			return ended.get(LONG_ENOUGH.toSeconds(), TimeUnit.SECONDS);
		} finally {
			caller.shutdownNow();
		}
	}

	private static void answerOnce(final ServerSocket node, final String response) {
		try (Socket client = node.accept()) {
			final BufferedReader request = new BufferedReader(
				new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));

			while (!request.readLine().isEmpty()) {
				continue;
			}

			client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
		} catch (IOException ex) {
			return;
		}
	}

	@Example
	@SneakyThrows
	public void endsWithOneReadableLineWhenTheNodeNeverAnswers() {
		try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			useNodeAt(silent.getLocalPort(), true);

			final HedgehogCli.Result cli = runInfo();

			assertThat(cli.exitCode(), equalTo(1));
			assertThat(cli.out(), emptyString());
			assertThat(cli.err().lines().toList().size(), equalTo(1));
			assertThat(cli.err(), startsWith("Could not reach the node at localhost:" + silent.getLocalPort() + ": "));
			assertThat(cli.err(), containsString("timed out"));
			cli.assertNoStackTrace();
		}
	}

	@Example
	@SneakyThrows
	public void saysThatTheAnswerCannotBeReadWhereTheBodyOfAnOkIsNoJson() {
		try (ServerSocket node = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			final String body = "<html>not the ledger</html>";
			final Thread answering = new Thread(() -> answerOnce(node, "HTTP/1.1 200 OK\r\n"
				+ "Content-Type: application/json\r\nContent-Length: " + body.length() + "\r\n"
				+ "Connection: close\r\n\r\n" + body));

			useNodeAt(node.getLocalPort(), false);
			answering.start();

			final HedgehogCli.Result cli = runInfo();

			assertThat(cli.exitCode(), equalTo(1));
			assertThat(cli.out(), emptyString());
			assertThat(cli.err().lines().toList().size(), equalTo(1));
			assertThat(cli.err(), startsWith("The node answered something this command cannot read: "));
			cli.assertNoStackTrace();
			answering.join();
		}
	}
}
