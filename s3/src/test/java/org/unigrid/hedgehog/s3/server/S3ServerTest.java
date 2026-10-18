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

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.unigrid.hedgehog.s3.server.S3Fixture.client;
import static org.unigrid.hedgehog.s3.server.S3Fixture.freePort;
import static org.unigrid.hedgehog.s3.server.S3Fixture.signed;
import static org.unigrid.hedgehog.s3.server.S3Fixture.start;
import software.amazon.awssdk.services.s3.S3Client;

public class S3ServerTest {
	@Example
	public void listsNoBucketsToAnSdkClient() throws Exception {
		final int port = freePort();

		try (S3Server server = start(port); S3Client client = client(port)) {
			assertThat(client.listBuckets().buckets(), empty());
		}
	}

	@Example
	public void answersListBucketsWithS3Xml() throws Exception {
		final int port = freePort();

		try (S3Server server = start(port)) {
			final HttpURLConnection connection = signed(port, "GET", "/");

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(connection.getContentType(), equalTo("application/xml"));
			assertThat(connection.getHeaderField("x-amz-request-id"), matchesPattern("[0-9A-F]{16}"));
			assertThat(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
				containsString("<ListAllMyBucketsResult "
				+ "xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Buckets/></ListAllMyBucketsResult>")
			);
		}
	}

	@Example
	public void answersAnUnsupportedOperationWithAnS3Error() throws Exception {
		final int port = freePort();

		try (S3Server server = start(port)) {
			final HttpURLConnection connection = signed(port, "GET", "/bucket/a%20key?torrent");

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_NOT_IMPLEMENTED));
			assertThat(connection.getContentType(), equalTo("application/xml"));
			assertThat(connection.getHeaderField("x-amz-id-2"), notNullValue());
			assertThat(connection.getHeaderField("Date"), endsWith(" GMT"));
			assertThat(new String(connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8),
				containsString("<Error><Code>NotImplemented</Code><Message>A header or query you provided implies "
				+ "functionality that is not implemented.</Message><Resource>/bucket/a key</Resource><RequestId>"
				+ connection.getHeaderField("x-amz-request-id") + "</RequestId></Error>")
			);
		}
	}

	@Example
	public void answersHeadWithoutAnErrorBody() throws Exception {
		final int port = freePort();

		try (S3Server server = start(port)) {
			final HttpURLConnection connection = signed(port, "HEAD", "/bucket/key");

			assertThat(connection.getResponseCode(), equalTo(HttpURLConnection.HTTP_NOT_IMPLEMENTED));
			assertThat(connection.getHeaderField("x-amz-request-id"), matchesPattern("[0-9A-F]{16}"));
			assertThat(connection.getContentLengthLong(), lessThanOrEqualTo(0L));
		}
	}

	@Example
	public void releasesItsPortWhenClosed() throws Exception {
		final int port = freePort();

		start(port).close();

		try (S3Server server = start(port); S3Client client = client(port)) {
			assertThat(client.listBuckets().buckets(), empty());
		}
	}
}
