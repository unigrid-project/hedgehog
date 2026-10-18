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
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CORSRule;

public class S3CorsTest {
	private static final String BUCKET = "photos";
	private static final String OBJECT = "/" + BUCKET + "/a.jpg";
	private static final String APP = "https://app.example.com";
	private static final String ELSEWHERE = "https://elsewhere.org";
	private static final String ORIGIN = "Origin";
	private static final String REQUEST_METHOD = "Access-Control-Request-Method";
	private static final String REQUEST_HEADERS = "Access-Control-Request-Headers";
	private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
	private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
	private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
	private static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";
	private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
	private static final String MAX_AGE = "Access-Control-Max-Age";
	private static final String VARY = "Origin, Access-Control-Request-Headers, Access-Control-Request-Method";
	private static final String NOT_ALLOWED = "CORSResponse: This CORS request is not allowed.";
	private static final int OK = HttpURLConnection.HTTP_OK;
	private static final int FORBIDDEN = HttpURLConnection.HTTP_FORBIDDEN;

	private static final CORSRule UPLOADS = CORSRule.builder().allowedOrigins("https://*.example.com")
		.allowedMethods("PUT", "POST").allowedHeaders("x-amz-*", "Content-Type").exposeHeaders("ETag")
		.maxAgeSeconds(3000).build();
	private static final CORSRule READS = CORSRule.builder().allowedOrigins("*").allowedMethods("GET", "HEAD")
		.build();

	private static S3Node node(final boolean withRules) throws Exception {
		final S3Node node = S3Node.start();

		node.getClient().createBucket(request -> request.bucket(BUCKET));
		node.getClient().putObject(request -> request.bucket(BUCKET).key("a.jpg"), RequestBody.fromString("jpeg"));

		if (withRules) {
			node.getClient().putBucketCors(request -> request.bucket(BUCKET).corsConfiguration(cors -> cors
				.corsRules(UPLOADS, READS)));
		}

		return node;
	}

	private static Map<String, String> asking(final String origin, final String method, final String headers) {
		final Map<String, String> asked = new HashMap<>();

		asked.put(ORIGIN, origin);
		asked.put(REQUEST_METHOD, method);
		asked.put(REQUEST_HEADERS, headers);
		asked.values().removeIf(value -> value == null);
		return asked;
	}

	private static RawHttp.Answer preflight(final int port, final String path, final Map<String, String> headers)
		throws IOException {

		return RawHttp.exchange(port, RawHttp.unsigned("OPTIONS", path, 0, headers), new byte[0]);
	}

	private static RawHttp.Answer signed(final int port, final String method, final String path,
		final Map<String, String> headers, final byte[] body) throws IOException {

		return RawHttp.exchange(port, RawHttp.signed(port, method, path, body.length, headers), body);
	}

	private static RawHttp.Answer virtualHost(final int port, final String method, final String path,
		final Map<String, String> headers) throws IOException {

		final StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + BUCKET + "."
			+ S3Fixture.DOMAIN + ":" + port + "\r\nContent-Length: 0\r\n"
		);

		headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
		return RawHttp.exchange(port, head.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1),
			new byte[0]
		);
	}

	/* An error without what differs between any two requests */
	private static String anonymous(final String error) {
		return error.replaceAll("<Resource>.*</Resource>|<RequestId>.*</RequestId>", "");
	}

	/* Only a preflight goes unsigned, so an OPTIONS request lacking what makes one is refused like any other unsigned
	   request, and tells nothing of the routes */
	@Example
	public void refusesAnUnsignedOptionsRequestThatIsNoPreflight() throws Exception {
		try (S3Node node = node(true)) {
			final List<RawHttp.Answer> refused = List.of(
				preflight(node.getPort(), OBJECT, asking(null, "GET", null)),
				preflight(node.getPort(), OBJECT, asking(APP, null, null)),
				preflight(node.getPort(), "/" + BUCKET, asking(null, null, null)),
				preflight(node.getPort(), "/", asking(APP, "GET", null)),
				preflight(node.getPort(), "/application.wadl", asking(null, null, null))
			);

			for (RawHttp.Answer answer : refused) {
				assertThat(answer.status(), equalTo(FORBIDDEN));
				assertThat(answer.body(), containsString("<Code>AccessDenied</Code>"));
				assertThat(answer.headers().get("Allow"), nullValue());
			}
		}
	}

	/* Otherwise a preflight would tell anyone which buckets there are */
	@Example
	public void refusesAPreflightToAMissingBucketAsOneToABucketWithoutRules() throws Exception {
		try (S3Node node = node(false)) {
			final RawHttp.Answer unruled = preflight(node.getPort(), OBJECT, asking(APP, "GET", null));
			final RawHttp.Answer missing = preflight(node.getPort(), "/other/a.jpg", asking(APP, "GET", null));

			for (RawHttp.Answer answer : List.of(unruled, missing, preflight(node.getPort(), "/other",
				asking(APP, "GET", null)))) {

				assertThat(answer.status(), equalTo(FORBIDDEN));
				assertThat(answer.body(), allOf(containsString("<Code>AccessForbidden</Code>"),
					containsString("CORSResponse: CORS is not enabled for this bucket."))
				);
			}

			assertThat(anonymous(missing.body()), equalTo(anonymous(unruled.body())));
			assertThat(new TreeSet<>(missing.headers().names()),
				equalTo(new TreeSet<>(unruled.headers().names()))
			);
		}
	}

	@Example
	public void servesNoDescriptionOfItsRoutes() throws Exception {
		try (S3Node node = node(true)) {
			final RawHttp.Answer unsigned = RawHttp.exchange(node.getPort(), RawHttp.unsigned("GET",
				"/application.wadl", 0), new byte[0]
			);
			final RawHttp.Answer description = signed(node.getPort(), "GET", "/application.wadl", Map.of(),
				new byte[0]
			);
			final RawHttp.Answer options = signed(node.getPort(), "OPTIONS", "/" + BUCKET, Map.of(),
				new byte[0]
			);

			assertThat(unsigned.status(), equalTo(FORBIDDEN));
			assertThat(description.status(), equalTo(HttpURLConnection.HTTP_NOT_FOUND));
			assertThat(description.body(), containsString("<Code>NoSuchBucket</Code>"));
			assertThat(options.status(), equalTo(HttpURLConnection.HTTP_BAD_REQUEST));
			assertThat(options.body(), containsString("Origin request header needed."));
			assertThat(options.headers().get("Allow"), nullValue());
		}
	}

	@Example
	public void clearsAPreflightByTheFirstRuleThatAllowsIt() throws Exception {
		try (S3Node node = node(true)) {
			for (String path : List.of(OBJECT, "/" + BUCKET)) {
				final RawHttp.Answer answer = preflight(node.getPort(), path, asking(APP, "PUT",
					"Content-Type, X-Amz-Date"));

				assertThat(answer.status(), equalTo(OK));
				assertThat(answer.length(), equalTo(0));
				assertThat(answer.headers().get(ALLOW_ORIGIN), equalTo(APP));
				assertThat(answer.headers().get(ALLOW_CREDENTIALS), equalTo("true"));
				assertThat(answer.headers().get(ALLOW_METHODS), equalTo("PUT, POST"));
				assertThat(answer.headers().get(ALLOW_HEADERS), equalTo("content-type, x-amz-date"));
				assertThat(answer.headers().get(EXPOSE_HEADERS), equalTo("ETag"));
				assertThat(answer.headers().get(MAX_AGE), equalTo("3000"));
				assertThat(answer.headers().get("Vary"), equalTo(VARY));
			}
		}
	}

	@Example
	public void answersAnyOriginOfAWildcardRuleWithAWildcard() throws Exception {
		try (S3Node node = node(true)) {
			final RawHttp.Answer answer = preflight(node.getPort(), OBJECT, asking(ELSEWHERE, "GET", null));

			assertThat(answer.status(), equalTo(OK));
			assertThat(answer.headers().get(ALLOW_ORIGIN), equalTo("*"));
			assertThat(answer.headers().get(ALLOW_CREDENTIALS), nullValue());
			assertThat(answer.headers().get(ALLOW_METHODS), equalTo("GET, HEAD"));
			assertThat(answer.headers().get(ALLOW_HEADERS), nullValue());
			assertThat(answer.headers().get(MAX_AGE), nullValue());
			assertThat(answer.headers().get("Vary"), equalTo(VARY));
		}
	}

	@Example
	public void refusesAPreflightNoRuleAllows() throws Exception {
		final List<Map<String, String>> refused = List.of(asking(ELSEWHERE, "PUT", null),
			asking(APP, "PUT", "x-custom"), asking("https://example.com", "PUT", null),
			asking(APP, "GET", "content-type"), asking(APP, "DELETE", null)
		);

		try (S3Node node = node(true)) {
			for (Map<String, String> headers : refused) {
				final RawHttp.Answer answer = preflight(node.getPort(), OBJECT, headers);

				assertThat(headers.toString(), answer.status(), equalTo(FORBIDDEN));
				assertThat(answer.body(), allOf(containsString("<Code>AccessForbidden</Code>"),
					containsString(NOT_ALLOWED))
				);
				assertThat(answer.headers().get(ALLOW_ORIGIN), nullValue());
			}
		}
	}

	@Example
	public void addsTheHeadersOfTheRuleARequestMatchesToItsAnswer() throws Exception {
		try (S3Node node = node(true)) {
			final Map<String, String> elsewhere = Map.of(ORIGIN, ELSEWHERE);
			final List<RawHttp.Answer> reads = List.of(
				signed(node.getPort(), "GET", OBJECT, elsewhere, new byte[0]),
				signed(node.getPort(), "GET", "/" + BUCKET + "/missing.jpg", elsewhere, new byte[0]),
				RawHttp.exchange(node.getPort(), RawHttp.unsigned("GET", OBJECT, 0, elsewhere), new byte[0])
			);

			assertThat(reads.stream().map(RawHttp.Answer::status).toList(), equalTo(List.of(OK,
				HttpURLConnection.HTTP_NOT_FOUND, FORBIDDEN))
			);
			assertThat(reads.stream().map(answer -> answer.headers().get(ALLOW_ORIGIN)).toList(),
				everyItem(equalTo("*"))
			);
			assertThat(reads.stream().map(answer -> answer.headers().get(ALLOW_METHODS)).toList(),
				everyItem(equalTo("GET, HEAD"))
			);
			assertThat(reads.stream().map(answer -> answer.headers().get("Vary")).toList(),
				everyItem(equalTo(VARY))
			);

			final RawHttp.Answer upload = signed(node.getPort(), "PUT", "/" + BUCKET + "/b.jpg",
				Map.of(ORIGIN, APP), "jpeg".getBytes(StandardCharsets.UTF_8)
			);

			assertThat(upload.status(), equalTo(OK));
			assertThat(upload.headers().get(ALLOW_ORIGIN), equalTo(APP));
			assertThat(upload.headers().get(ALLOW_CREDENTIALS), equalTo("true"));
			assertThat(upload.headers().get(EXPOSE_HEADERS), equalTo("ETag"));
			assertThat(upload.headers().get(MAX_AGE), equalTo("3000"));
		}
	}

	@Example
	public void leavesTheHeadersOutWhereNoRuleMatches() throws Exception {
		try (S3Node node = node(true)) {
			assertThat(signed(node.getPort(), "GET", OBJECT, Map.of(), new byte[0]).headers().get(ALLOW_ORIGIN),
				nullValue()
			);
			assertThat(signed(node.getPort(), "DELETE", OBJECT, Map.of(ORIGIN, APP), new byte[0]).headers()
				.get(ALLOW_ORIGIN), nullValue()
			);
		}
	}

	@Example
	public void answersAVirtualHostBucketByTheRulesOfThatBucket() throws Exception {
		try (S3Node node = node(true)) {
			final RawHttp.Answer refused = virtualHost(node.getPort(), "GET", "/a.jpg",
				Map.of(ORIGIN, ELSEWHERE)
			);

			assertThat(refused.status(), equalTo(FORBIDDEN));
			assertThat(refused.headers().get(ALLOW_ORIGIN), equalTo("*"));

			for (String path : List.of("/", "/a.jpg")) {
				final RawHttp.Answer cleared = virtualHost(node.getPort(), "OPTIONS", path,
					asking(APP, "PUT", null)
				);

				assertThat(cleared.status(), equalTo(OK));
				assertThat(cleared.headers().get(ALLOW_ORIGIN), equalTo(APP));
			}
		}
	}

	@Example
	public void authenticatesWhatOnlyLooksLikeAPreflight() throws Exception {
		try (S3Node node = node(true)) {
			final Map<String, String> overridden = new HashMap<>(asking(APP, "PUT", null));

			overridden.put("X-HTTP-Method-Override", "OPTIONS");

			for (String method : List.of("GET", "PUT", "DELETE", "options")) {
				final RawHttp.Answer answer = RawHttp.exchange(node.getPort(),
					RawHttp.unsigned(method, OBJECT, 0, overridden), new byte[0]
				);

				assertThat(method, answer.status(), equalTo(FORBIDDEN));
				assertThat(answer.body(), containsString("<Code>AccessDenied</Code>"));
			}

			final Map<String, String> deleting = new HashMap<>(asking(APP, "DELETE", null));

			deleting.put("X-HTTP-Method-Override", "DELETE");

			assertThat(preflight(node.getPort(), OBJECT, deleting).status(), equalTo(FORBIDDEN));
			assertThat(node.getClient().headObject(request -> request.bucket(BUCKET).key("a.jpg"))
				.contentLength(), equalTo(4L)
			);
		}
	}
}
