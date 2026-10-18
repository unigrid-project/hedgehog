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

package org.unigrid.hedgehog.s3.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.s3.jqwik.Expect;

/* Longer than what Jersey buffers to learn the length of a body by itself */
public class S3ResponseLengthTest {
	private static final int LENGTH = 100_000;
	private static final int TIMEOUT_MILLIS = 30_000;

	private static NettyLeaks leaks;

	@BeforeContainer
	public static void watchLeaks() throws ReflectiveOperationException {
		leaks = NettyLeaks.watch();
	}

	@AfterContainer
	public static void stopWatchingLeaks() {
		leaks.close();
	}

	private static String path(final long declared) {
		return "/sized/" + LENGTH + "/" + declared;
	}

	/* Whatever the server sends must not parse as a complete response */
	private static void assertCut(final long declared) throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, SizedResource.class);
			Socket socket = new Socket(S3Fixture.HOST, port)) {

			socket.setSoTimeout(TIMEOUT_MILLIS);
			socket.getOutputStream().write(RawHttp.signed(port, "GET", path(declared), 0));
			Expect.assertThrows(IOException.class, () -> RawHttp.answers(socket.getInputStream(), 1));
		}

		assertThat(leaks.collect(), empty());
	}

	@Example
	public void keepsTheLengthAResourceDeclaresForALargeBody() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, SizedResource.class)) {
			final HttpURLConnection connection = S3Fixture.signed(port, "GET", path(LENGTH));

			try (InputStream body = connection.getInputStream()) {
				assertThat(connection.getHeaderField("Content-Length"), equalTo(Integer.toString(LENGTH)));
				assertThat(connection.getHeaderField("Transfer-Encoding"), nullValue());
				assertThat(body.readAllBytes().length, equalTo(LENGTH));
			}
		}

		assertThat(leaks.collect(), empty());
	}

	@Example
	public void keepsTheLengthOfALargeBodyInAnswerToHead() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port, SizedResource.class)) {
			final HttpURLConnection connection = S3Fixture.signed(port, "HEAD", path(LENGTH));

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(connection.getHeaderField("Content-Length"), equalTo(Integer.toString(LENGTH)));
			assertThat(connection.getHeaderField("Transfer-Encoding"), nullValue());
		}

		assertThat(leaks.collect(), empty());
	}

	@Example
	public void cutsABodyShorterThanTheLengthItDeclares() throws Exception {
		assertCut(LENGTH + 1);
	}

	@Example
	public void cutsABodyLongerThanTheLengthItDeclares() throws Exception {
		assertCut(LENGTH - 1);
	}
}
