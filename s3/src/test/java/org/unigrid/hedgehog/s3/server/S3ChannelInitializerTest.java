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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import software.amazon.awssdk.services.s3.S3Client;

public class S3ChannelInitializerTest {
	private static final String CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n";
	private static final int TIMEOUT_MILLIS = 10_000;

	@Example
	public void invitesTheBodyOfARequestThatExpectsToContinue() throws Exception {
		final int port = S3Fixture.freePort();

		try (S3Server server = S3Fixture.start(port); Socket socket = new Socket(S3Fixture.HOST, port)) {
			final OutputStream out = socket.getOutputStream();
			final InputStream in = socket.getInputStream();

			socket.setSoTimeout(TIMEOUT_MILLIS);
			out.write(("PUT /bucket/key HTTP/1.1\r\nHost: " + S3Fixture.HOST + "\r\nContent-Length: 5\r\n"
				+ "Expect: 100-continue\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			assertThat(new String(in.readNBytes(CONTINUE.length()), StandardCharsets.ISO_8859_1), equalTo(CONTINUE));
			out.write("hello".getBytes(StandardCharsets.ISO_8859_1));
			out.flush();

			assertThat(new String(in.readAllBytes(), StandardCharsets.ISO_8859_1),
				matchesPattern("(?s)HTTP/1\\.1 [45]\\d\\d .*<Error>.*")
			);
		}
	}

	@Example
	public void servesALongKeyWithALongQuery() throws Exception {
		final int port = S3Fixture.freePort();
		final String key = "€".repeat(341);

		try (S3Server server = S3Fixture.start(port, EchoResource.class); S3Client client = S3Fixture.client(port)) {
			assertThat(client.getObjectAsBytes(request -> request.bucket("bucket").key(key)
				.responseContentDisposition("attachment; filename=\"" + "x".repeat(2048) + "\"")).asUtf8String(),
				equalTo("bucket/" + key)
			);
		}
	}
}
