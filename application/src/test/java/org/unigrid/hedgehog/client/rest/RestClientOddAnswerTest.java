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

package org.unigrid.hedgehog.client.rest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

/* A node, or a proxy in front of it, may answer with a status that no one has a name for */
public class RestClientOddAnswerTest {
	private static final int NO_STATUS = 599;

	private static void answerOnce(final ServerSocket node) {
		try (Socket client = node.accept()) {
			final BufferedReader request = new BufferedReader(
				new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));

			while (!request.readLine().isEmpty()) {
				continue;
			}

			client.getOutputStream().write(("HTTP/1.1 " + NO_STATUS + " Odd\r\nContent-Length: 0\r\n"
				+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
		} catch (IOException ex) {
			return;
		}
	}

	@Example
	@SneakyThrows
	public void shouldReportAStatusThatHasNoNameAsAnOddAnswer() {
		try (ServerSocket node = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			RestClient rest = new RestClient("localhost", node.getLocalPort(), false, "token")) {

			final Thread answering = new Thread(() -> answerOnce(node));

			answering.start();

			final ResponseOddityException oddity = assertThrows(ResponseOddityException.class,
				() -> rest.get("/ledger"));

			assertThat(oddity.getStatusCode(), equalTo(NO_STATUS));
			answering.join();
		}
	}
}
