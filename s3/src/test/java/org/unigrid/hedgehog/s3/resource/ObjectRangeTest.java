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

package org.unigrid.hedgehog.s3.resource;

import java.net.HttpURLConnection;
import java.util.Arrays;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.s3.server.S3Node;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

public class ObjectRangeTest {
	private static final String BUCKET = "photos";
	private static final byte[] DATA = new byte[1000];
	private static final int PARTIAL = 206;
	private static final int RANGE_NOT_SATISFIABLE = 416;

	static {
		Arrays.fill(DATA, (byte) 7);

		for (int i = 0; i < DATA.length; i += 3) {
			DATA[i] = (byte) i;
		}
	}

	private static ResponseBytes<GetObjectResponse> range(final S3Node node, final String range) {
		return node.getClient().getObjectAsBytes(request -> request.bucket(BUCKET).key("key").range(range));
	}

	private static S3Node node() throws Exception {
		final S3Node node = ObjectResourceTest.node();

		ObjectResourceTest.put(node.getClient(), "key", DATA);
		return node;
	}

	@Property(tries = 10)
	public void servesAnyRangeWithinTheObject(@ForAll @IntRange(max = 999) int first,
		@ForAll @IntRange(max = 999) int last) throws Exception {

		try (S3Node node = node()) {
			final int from = Math.min(first, last);
			final int to = Math.max(first, last);
			final ResponseBytes<GetObjectResponse> read = range(node, "bytes=" + from + "-" + to);

			assertThat(read.asByteArray(), equalTo(Arrays.copyOfRange(DATA, from, to + 1)));
			assertThat(read.response().contentRange(), equalTo("bytes " + from + "-" + to + "/1000"));
			assertThat(read.response().contentLength(), equalTo((long) to - from + 1));
			assertThat(read.response().sdkHttpResponse().statusCode(), equalTo(PARTIAL));
		}
	}

	@Example
	public void servesOpenAndSuffixRanges() throws Exception {
		try (S3Node node = node()) {
			assertThat(range(node, "bytes=900-").asByteArray(), equalTo(Arrays.copyOfRange(DATA, 900, 1000)));
			assertThat(range(node, "bytes=-50").asByteArray(), equalTo(Arrays.copyOfRange(DATA, 950, 1000)));
			assertThat(range(node, "bytes=-5000").asByteArray(), equalTo(DATA));
			assertThat(range(node, "bytes=990-5000").response().contentRange(), equalTo("bytes 990-999/1000"));
		}
	}

	@Example
	public void servesTheWholeObjectForARangeItCannotRead() throws Exception {
		try (S3Node node = node()) {
			final ResponseBytes<GetObjectResponse> read = range(node, "bytes=5-2");

			assertThat(read.asByteArray(), equalTo(DATA));
			assertThat(read.response().sdkHttpResponse().statusCode(), equalTo(HttpURLConnection.HTTP_OK));
		}
	}

	@Example
	public void refusesARangePastTheEnd() throws Exception {
		try (S3Node node = node()) {
			final S3Exception refused = assertThrows(S3Exception.class, () -> range(node, "bytes=1000-"));

			assertThat(refused.statusCode(), equalTo(RANGE_NOT_SATISFIABLE));
			assertThat(refused.awsErrorDetails().errorCode(), equalTo("InvalidRange"));
			assertThat(refused.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("Content-Range").orElseThrow(),
				equalTo("bytes */1000")
			);
		}
	}

	@Example
	public void answersAHeadForARange() throws Exception {
		try (S3Node node = node()) {
			final HeadObjectResponse head = node.getClient().headObject(request -> request.bucket(BUCKET).key("key")
				.range("bytes=10-19"));

			assertThat(head.contentLength(), equalTo(10L));
			assertThat(head.contentRange(), equalTo("bytes 10-19/1000"));
		}
	}
}
