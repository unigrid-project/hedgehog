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

package org.unigrid.hedgehog.s3.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class SigV4Test {
	private static final String SUITE = "/aws-sig-v4-test-suite/";
	private static final String SUITE_SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
	private static final String S3_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
	private static final String S3_TIMESTAMP = "20130524T000000Z";
	private static final String S3_SCOPE = "20130524/us-east-1/s3/aws4_request";
	private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private static final Pattern AUTHORIZATION = Pattern.compile(
		"AWS4-HMAC-SHA256 Credential=\\w+/((\\d{8})/([\\w-]+)/(\\w+)/aws4_request), SignedHeaders=([^,]+), "
		+ "Signature=(\\p{XDigit}{64})"
	);

	private static final List<String> VECTORS = List.of("get-header-key-duplicate", "get-header-value-order",
		"get-header-value-trim", "get-unreserved", "get-utf8", "get-vanilla", "get-vanilla-empty-query-key",
		"get-vanilla-query", "get-vanilla-query-order-encoded", "get-vanilla-query-order-key",
		"get-vanilla-query-order-key-case", "get-vanilla-query-order-value", "get-vanilla-query-unreserved",
		"get-vanilla-utf8-query", "post-header-key-case", "post-header-key-sort", "post-header-value-case",
		"post-vanilla", "post-vanilla-empty-query-value", "post-vanilla-query", "post-x-www-form-urlencoded",
		"post-x-www-form-urlencoded-parameters"
	);

	private record Request(String method, String path, String query, Map<String, List<String>> headers, String body) {
		static Request parse(final String text) {
			final String[] lines = text.split("\n", -1);
			final String[] start = lines[0].split(" ");
			final String[] target = start[1].split("\\?", 2);
			final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			int line = 1;

			for (; line < lines.length && !lines[line].isEmpty(); line++) {
				final String[] header = lines[line].split(":", 2);
				headers.computeIfAbsent(header[0], name -> new ArrayList<>()).add(header[1]);
			}

			return new Request(start[0], target[0], target.length > 1 ? target[1] : null, headers,
				String.join("\n", Arrays.copyOfRange(lines, Math.min(line + 1, lines.length), lines.length))
			);
		}
	}

	private static String read(final String vector, final String extension) throws IOException {
		try (InputStream in = SigV4Test.class.getResourceAsStream(SUITE + vector + "/" + vector + "." + extension)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Provide
	public Arbitrary<String> provideVector() {
		return Arbitraries.of(VECTORS);
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void reproducesTheSignatureTestSuite(@ForAll("provideVector") String vector) throws IOException {
		final Request request = Request.parse(read(vector, "req"));
		final Matcher authorization = AUTHORIZATION.matcher(read(vector, "authz"));

		assertThat(authorization.matches(), equalTo(true));

		final String canonical = SigV4.canonicalRequest(request.method(), request.path(), request.query(),
			request.headers()::get, List.of(authorization.group(5).split(";")),
			SigV4.sha256Hex(request.body().getBytes(StandardCharsets.UTF_8)), Set.of()
		);
		final String timestamp = request.headers().get("X-Amz-Date").getFirst();
		final String stringToSign = SigV4.stringToSign(timestamp, authorization.group(1), canonical);
		final byte[] key = SigV4.signingKey(SUITE_SECRET, authorization.group(2), authorization.group(3),
			authorization.group(4)
		);

		assertThat(canonical, equalTo(read(vector, "creq")));
		assertThat(stringToSign, equalTo(read(vector, "sts")));
		assertThat(SigV4.sign(key, stringToSign), equalTo(authorization.group(6)));
	}

	private static Map<String, List<String>> headers(final String... namesAndValues) {
		final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		for (int i = 0; i < namesAndValues.length; i += 2) {
			headers.put(namesAndValues[i], List.of(namesAndValues[i + 1]));
		}

		return headers;
	}

	private static String signS3Example(final String method, final String path, final String query,
		final Map<String, List<String>> headers, final String payload, final String canonical, final String digest) {

		final String request = SigV4.canonicalRequest(method, path, query, headers::get,
			headers.keySet().stream().toList(), payload, Set.of()
		);
		final String stringToSign = SigV4.stringToSign(S3_TIMESTAMP, S3_SCOPE, request);

		assertThat(request, equalTo(canonical));
		assertThat(stringToSign, equalTo("AWS4-HMAC-SHA256\n" + S3_TIMESTAMP + "\n" + S3_SCOPE + "\n" + digest));
		return SigV4.sign(SigV4.signingKey(S3_SECRET, "20130524", "us-east-1", "s3"), stringToSign);
	}

	@Example
	public void signsTheGetObjectExampleOfS3() {
		assertThat(signS3Example("GET", "/test.txt", null, headers("host", "examplebucket.s3.amazonaws.com",
			"range", "bytes=0-9", "x-amz-content-sha256", EMPTY_SHA256, "x-amz-date", S3_TIMESTAMP), EMPTY_SHA256,
			"GET\n/test.txt\n\nhost:examplebucket.s3.amazonaws.com\nrange:bytes=0-9\nx-amz-content-sha256:"
			+ EMPTY_SHA256 + "\nx-amz-date:20130524T000000Z\n\nhost;range;x-amz-content-sha256;x-amz-date\n"
			+ EMPTY_SHA256, "7344ae5b7ee6c3e7e6b0fe0640412a37625d1fbfff95c48bbb2dc43964946972"),
			equalTo("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")
		);
	}

	@Example
	public void signsThePutObjectExampleOfS3() {
		final String payload = SigV4.sha256Hex("Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8));

		assertThat(signS3Example("PUT", "/test$file.text", null, headers("date", "Fri, 24 May 2013 00:00:00 GMT",
			"host", "examplebucket.s3.amazonaws.com", "x-amz-content-sha256", payload, "x-amz-date", S3_TIMESTAMP,
			"x-amz-storage-class", "REDUCED_REDUNDANCY"), payload, "PUT\n/test%24file.text\n\n"
			+ "date:Fri, 24 May 2013 00:00:00 GMT\nhost:examplebucket.s3.amazonaws.com\nx-amz-content-sha256:"
			+ payload + "\nx-amz-date:20130524T000000Z\nx-amz-storage-class:REDUCED_REDUNDANCY\n\n"
			+ "date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class\n" + payload,
			"9e0e90d9c76de8fa5b200d8c849cd5b8dc7a3be3951ddb7f6a76b4158342019d"),
			equalTo("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd")
		);
	}

	@Example
	public void signsTheGetBucketLifecycleExampleOfS3() {
		assertThat(signS3Example("GET", "/", "lifecycle", headers("host", "examplebucket.s3.amazonaws.com",
			"x-amz-content-sha256", EMPTY_SHA256, "x-amz-date", S3_TIMESTAMP), EMPTY_SHA256,
			"GET\n/\nlifecycle=\nhost:examplebucket.s3.amazonaws.com\nx-amz-content-sha256:" + EMPTY_SHA256
			+ "\nx-amz-date:20130524T000000Z\n\nhost;x-amz-content-sha256;x-amz-date\n" + EMPTY_SHA256,
			"9766c798316ff2757b517bc739a67f6213b4ab36dd5da2f94eaebf79c77395ca"),
			equalTo("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543")
		);
	}

	@Example
	public void signsTheListObjectsExampleOfS3() {
		assertThat(signS3Example("GET", "/", "max-keys=2&prefix=J", headers("host",
			"examplebucket.s3.amazonaws.com", "x-amz-content-sha256", EMPTY_SHA256, "x-amz-date", S3_TIMESTAMP),
			EMPTY_SHA256, "GET\n/\nmax-keys=2&prefix=J\nhost:examplebucket.s3.amazonaws.com\nx-amz-content-sha256:"
			+ EMPTY_SHA256 + "\nx-amz-date:20130524T000000Z\n\nhost;x-amz-content-sha256;x-amz-date\n"
			+ EMPTY_SHA256, "df57d21db20da04d7fa30298dd4488ba3a2b47ca3a489c74750e0f1e7df1b9b7"),
			equalTo("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7")
		);
	}

	@Example
	public void signsThePresignedUrlExampleOfS3() {
		final String query = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524"
			+ "%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400"
			+ "&X-Amz-SignedHeaders=host&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404";
		final String canonical = SigV4.canonicalRequest("GET", "/test.txt", query,
			headers("host", "examplebucket.s3.amazonaws.com")::get, List.of("host"), "UNSIGNED-PAYLOAD",
			Set.of("X-Amz-Signature")
		);
		final String stringToSign = SigV4.stringToSign(S3_TIMESTAMP, S3_SCOPE, canonical);

		assertThat(canonical, equalTo("GET\n/test.txt\nX-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential="
			+ "AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z"
			+ "&X-Amz-Expires=86400&X-Amz-SignedHeaders=host\nhost:examplebucket.s3.amazonaws.com\n\nhost\n"
			+ "UNSIGNED-PAYLOAD")
		);
		assertThat(stringToSign, equalTo("AWS4-HMAC-SHA256\n" + S3_TIMESTAMP + "\n" + S3_SCOPE + "\n"
			+ "3bfa292879f6447bbcda7001decf97f4a54dc650c8942174ae0a9121cf58ad04")
		);
		assertThat(SigV4.sign(SigV4.signingKey(S3_SECRET, "20130524", "us-east-1", "s3"), stringToSign),
			equalTo("aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404")
		);
	}

	@Example
	public void encodesOnceAndKeepsTheSlashesOfAPath() {
		assertThat(SigV4.canonicalUri("/bucket/a%20b/c+d%2Be~f%7E/%E2%82%AC"),
			equalTo("/bucket/a%20b/c%2Bd%2Be~f~/%E2%82%AC")
		);
		assertThat(SigV4.canonicalUri(""), equalTo("/"));
		assertThat(SigV4.uriEncode("a/b c*", false), equalTo("a%2Fb%20c%2A"));
	}

	@Example
	public void leavesOutTheExcludedQueryParameters() {
		assertThat(SigV4.canonicalQuery("b=2&X-Amz-Signature=abc&a=1&uploads", Set.of("X-Amz-Signature")),
			equalTo("a=1&b=2&uploads=")
		);
		assertThat(SigV4.canonicalQuery("prefix=a+b&marker=a%2Bb", Set.of()), equalTo("marker=a%2Bb&prefix=a%20b"));
	}
}
