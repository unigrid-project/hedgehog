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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.s3.auth.SigV4;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;

/* Browser form uploads, signed the way the AWS documentation signs its example form */
public class S3FormUploadTest {
	private static final String BUCKET = "photos";
	private static final String BOUNDARY = "----FormBoundary7MA4YWxkTrZu0gW";
	private static final String REGION = "us-east-1";
	private static final String TAGGING = "<Tagging><TagSet><Tag><Key>album</Key><Value>summer</Value></Tag></TagSet>"
		+ "</Tagging>";
	private static final byte[] PHOTO = new byte[70_000];
	private static final Duration HOUR = Duration.ofHours(1);

	static {
		new Random(PHOTO.length).nextBytes(PHOTO);
	}

	/* What the form holds besides the file, in the order a browser sends it */
	private record Form(Map<String, String> fields) {
		private Form with(final String name, final String value) {
			final Map<String, String> changed = new LinkedHashMap<>(fields);

			changed.put(name, value);
			return new Form(changed);
		}

		private byte[] body(final String filename, final byte[] file) {
			final ByteArrayOutputStream body = new ByteArrayOutputStream();

			fields.forEach((name, value) -> body.writeBytes(field(name, value)));

			body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
				+ filename + "\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			body.writeBytes(file);
			body.writeBytes(("\r\n--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"submit\"\r\n\r\n"
				+ "Upload\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
			return body.toByteArray();
		}
	}

	private static byte[] field(final String name, final String value) {
		return ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
			+ "\r\n").getBytes(StandardCharsets.UTF_8);
	}

	/* The fields signed by a policy of the given conditions and those every policy holds, scoped to a day */
	private static Form signed(final Map<String, String> fields, final Instant expiration, final String day,
		final String... conditions) {

		final String date = SigV4.TIMESTAMP.format(Instant.now());
		final String credential = S3Fixture.ACCESS_KEY + "/" + day + "/" + REGION + "/s3/aws4_request";
		final List<String> all = new ArrayList<>(List.of(conditions));

		all.addAll(List.of("{\"bucket\": \"" + BUCKET + "\"}", "{\"x-amz-algorithm\": \"" + SigV4.ALGORITHM + "\"}",
			"{\"x-amz-credential\": \"" + credential + "\"}", "{\"x-amz-date\": \"" + date + "\"}"
		));

		final String policy = Base64.getEncoder().encodeToString(("{\"expiration\": \"" + expiration + "\", "
			+ "\"conditions\": [" + String.join(", ", all) + "]}").getBytes(StandardCharsets.UTF_8)
		);
		final Map<String, String> form = new LinkedHashMap<>(fields);

		form.put("policy", policy);
		form.put("x-amz-algorithm", SigV4.ALGORITHM);
		form.put("x-amz-credential", credential);
		form.put("x-amz-date", date);
		form.put("x-amz-signature", SigV4.sign(SigV4.signingKey(S3Fixture.SECRET_KEY, day, REGION, SigV4.SERVICE),
			policy
		));
		return new Form(form);
	}

	private static Form signed(final Map<String, String> fields, final String... conditions) {
		return signed(fields, Instant.now().plus(HOUR), SigV4.TIMESTAMP.format(Instant.now()).substring(0, 8),
			conditions
		);
	}

	private static Form photo(final String... conditions) {
		final List<String> all = new ArrayList<>(List.of("[\"starts-with\", \"$key\", \"uploads/\"]"));

		all.addAll(List.of(conditions));
		return signed(Map.of("key", "uploads/${filename}"), all.toArray(String[]::new));
	}

	private static RawHttp.Answer post(final int port, final String host, final String path, final String type,
		final byte[] body) throws IOException {

		final byte[] head = ("POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nContent-Type: " + type
			+ "\r\nContent-Length: " + body.length + "\r\nOrigin: https://app.example.com\r\n\r\n")
			.getBytes(StandardCharsets.ISO_8859_1);

		return RawHttp.exchange(port, head, body);
	}

	private static RawHttp.Answer post(final S3Node node, final String path, final byte[] body) throws IOException {
		return post(node.getPort(), S3Fixture.HOST, path, "multipart/form-data; boundary=" + BOUNDARY, body);
	}

	private static RawHttp.Answer post(final S3Node node, final Form form, final byte[] file) throws IOException {
		return post(node, "/" + BUCKET, form.body("photo.jpg", file));
	}

	private static S3Node node() throws Exception {
		final S3Node node = S3Node.start();

		node.getClient().createBucket(request -> request.bucket(BUCKET));
		return node;
	}

	private static String md5(final byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
	}

	private static List<S3Object> objects(final S3Node node) {
		return node.getClient().listObjectsV2(request -> request.bucket(BUCKET)).contents();
	}

	/* A refused upload leaves neither a blob nor an index entry behind */
	private static void refused(final S3Node node, final RawHttp.Answer answer, final int status, final String code) {
		assertThat(answer.body(), answer.status(), equalTo(status));
		assertThat(answer.body(), containsString("<Code>" + code + "</Code>"));
		assertThat(node.getBlobs().size(), equalTo(0));
		assertThat(objects(node), empty());
	}

	@Example
	public void storesTheFileOfASignedFormWithItsHeadersMetadataAndTags() throws Exception {
		try (S3Node node = node()) {
			final Form form = photo("[\"eq\", \"$Content-Type\", \"image/jpeg\"]",
				"[\"starts-with\", \"$x-amz-meta-taken-by\", \"\"]", "{\"success_action_status\": \"201\"}",
				"{\"tagging\": \"" + TAGGING.replace("\"", "\\\"") + "\"}",
				"[\"content-length-range\", 1, 100000]"
			).with("Content-Type", "image/jpeg").with("x-amz-meta-taken-by", "alice")
				.with("success_action_status", "201").with("tagging", TAGGING);
			final RawHttp.Answer answer = post(node, form, PHOTO);
			final String etag = "\"" + md5(PHOTO) + "\"";

			assertThat(answer.status(), equalTo(HttpURLConnection.HTTP_CREATED));
			assertThat(answer.body(), allOf(containsString("<PostResponse><Location>"),
				containsString("/" + BUCKET + "/uploads/photo.jpg</Location><Bucket>" + BUCKET + "</Bucket>"
				+ "<Key>uploads/photo.jpg</Key><ETag>" + etag + "</ETag></PostResponse>"))
			);
			assertThat(answer.headers().get("ETag"), equalTo(etag));
			assertThat(answer.headers().get("Location"), endsWith("/" + BUCKET + "/uploads/photo.jpg"));
			assertThat(answer.headers().get("Access-Control-Allow-Origin"), equalTo(null));

			final ResponseBytes<GetObjectResponse> stored = node.getClient().getObjectAsBytes(request -> request
				.bucket(BUCKET).key("uploads/photo.jpg"));

			assertThat(stored.asByteArray(), equalTo(PHOTO));
			assertThat(stored.response().eTag(), equalTo(etag));
			assertThat(stored.response().contentType(), equalTo("image/jpeg"));
			assertThat(stored.response().metadata(), equalTo(Map.of("taken-by", "alice")));
			assertThat(node.getClient().getObjectTagging(request -> request.bucket(BUCKET)
				.key("uploads/photo.jpg")).tagSet(),
				contains(Tag.builder().key("album").value("summer").build())
			);
		}
	}

	@Example
	public void answersAsTheFormAsks() throws Exception {
		try (S3Node node = node()) {
			final RawHttp.Answer plain = post(node, photo(), PHOTO);
			final RawHttp.Answer ok = post(node, photo("{\"success_action_status\": \"200\"}")
				.with("success_action_status", "200"), new byte[0]);
			final String target = "https://app.example.com/done?from=form";
			final RawHttp.Answer redirect = post(node, photo("{\"success_action_redirect\": \"" + target + "\"}")
				.with("success_action_redirect", target), "jpeg".getBytes(StandardCharsets.UTF_8));

			assertThat(plain.status(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(ok.status(), equalTo(HttpURLConnection.HTTP_OK));
			assertThat(ok.length(), equalTo(0));
			assertThat(redirect.status(), equalTo(HttpURLConnection.HTTP_SEE_OTHER));
			assertThat(redirect.headers().get("Location"), equalTo(target + "&bucket=" + BUCKET
				+ "&key=uploads%2Fphoto.jpg&etag=%22" + md5("jpeg".getBytes(StandardCharsets.UTF_8)) + "%22")
			);
			assertThat(node.getClient().headObject(request -> request.bucket(BUCKET).key("uploads/photo.jpg"))
				.contentLength(), equalTo(4L)
			);
		}
	}

	@Example
	public void locksAndVersionsWhatAFormStoresAsAPutWould() throws Exception {
		try (S3Node node = S3Node.start()) {
			final S3Client client = node.getClient();
			final String version = "x-amz-version-id";
			final String hold = "x-amz-object-lock-legal-hold";

			client.createBucket(request -> request.bucket(BUCKET).objectLockEnabledForBucket(true));
			client.putObjectLockConfiguration(request -> request.bucket(BUCKET).objectLockConfiguration(lock ->
				lock.objectLockEnabled(ObjectLockEnabled.ENABLED).rule(rule -> rule.defaultRetention(
				retention -> retention.mode(ObjectLockRetentionMode.GOVERNANCE).days(1)))));

			final RawHttp.Answer first = post(node, photo(), PHOTO);
			final RawHttp.Answer held = post(node, photo("{\"" + hold + "\": \"ON\"}").with(hold, "ON"), PHOTO);

			assertThat(first.status(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(held.headers().get(version), not(equalTo(first.headers().get(version))));
			assertThat(client.listObjectVersions(request -> request.bucket(BUCKET)).versions(), hasSize(2));
			assertThat(client.getObjectRetention(request -> request.bucket(BUCKET).key("uploads/photo.jpg")
				.versionId(first.headers().get(version))).retention().mode(),
				equalTo(ObjectLockRetentionMode.GOVERNANCE)
			);
			assertThat(client.getObjectLegalHold(request -> request.bucket(BUCKET).key("uploads/photo.jpg")
				.versionId(held.headers().get(version))).legalHold().status(),
				equalTo(ObjectLockLegalHoldStatus.ON)
			);
		}
	}

	@Example
	public void takesAFormPostedToAVirtualHostBucket() throws Exception {
		try (S3Node node = node()) {
			final RawHttp.Answer answer = post(node.getPort(), BUCKET + "." + S3Fixture.DOMAIN, "/",
				"multipart/form-data; boundary=\"" + BOUNDARY + "\"", photo().body("photo.jpg", PHOTO)
			);

			assertThat(answer.status(), equalTo(HttpURLConnection.HTTP_NO_CONTENT));
			assertThat(objects(node).stream().map(S3Object::key).toList(), contains("uploads/photo.jpg"));
		}
	}

	@Example
	public void refusesAFormItsPolicyDoesNotAllow() throws Exception {
		final String day = SigV4.TIMESTAMP.format(Instant.now()).substring(0, 8);
		final String yesterday = SigV4.TIMESTAMP.format(Instant.now().minus(Duration.ofDays(1))).substring(0, 8);
		final Form form = photo();
		final String signature = form.fields().get("x-amz-signature");
		final Map<String, String> key = Map.of("key", "uploads/${filename}");
		final String prefix = "[\"starts-with\", \"$key\", \"uploads/\"]";

		try (S3Node node = node()) {
			refused(node, post(node, signed(key, Instant.now().minus(HOUR), day, prefix), PHOTO),
				HttpURLConnection.HTTP_FORBIDDEN, "AccessDenied"
			);
			refused(node, post(node, form.with("x-amz-signature", signature.replace(signature.charAt(0),
				signature.charAt(0) == 'a' ? 'b' : 'a')), PHOTO), HttpURLConnection.HTTP_FORBIDDEN,
				"SignatureDoesNotMatch"
			);
			refused(node, post(node, signed(key, Instant.now().plus(HOUR), yesterday, prefix), PHOTO),
				HttpURLConnection.HTTP_BAD_REQUEST, "InvalidArgument"
			);
			refused(node, post(node, form.with("x-amz-meta-extra", "1"), PHOTO),
				HttpURLConnection.HTTP_FORBIDDEN, "AccessDenied"
			);
			refused(node, post(node, signed(Map.of("key", "elsewhere/${filename}"), prefix), PHOTO),
				HttpURLConnection.HTTP_FORBIDDEN, "AccessDenied"
			);
			refused(node, post(node, photo("{\"acl\": \"public-read\"}").with("acl", "public-read"), PHOTO),
				HttpURLConnection.HTTP_BAD_REQUEST, "AccessControlListNotSupported"
			);
		}
	}

	@Example
	public void refusesAFileOutsideTheLengthRangeWhileItStreams() throws Exception {
		try (S3Node node = node()) {
			final RawHttp.Answer large = post(node, photo("[\"content-length-range\", 0, 1000]"), PHOTO);

			refused(node, large, HttpURLConnection.HTTP_BAD_REQUEST, "EntityTooLarge");
			refused(node, post(node, photo("[\"content-length-range\", 100, 1000]"), new byte[10]),
				HttpURLConnection.HTTP_BAD_REQUEST, "EntityTooSmall"
			);
		}
	}

	@Example
	public void refusesAFormThatIsNoUpload() throws Exception {
		try (S3Node node = node()) {
			final ByteArrayOutputStream fieldsOnly = new ByteArrayOutputStream();

			photo().fields().forEach((name, value) -> fieldsOnly.writeBytes(field(name, value)));
			fieldsOnly.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

			refused(node, post(node, "/" + BUCKET, fieldsOnly.toByteArray()), HttpURLConnection.HTTP_BAD_REQUEST,
				"IncorrectNumberOfFilesInPostRequest"
			);
			refused(node, post(node, photo().with("x-ignore-note", "x".repeat(25_000)), PHOTO),
				HttpURLConnection.HTTP_BAD_REQUEST, "MaxPostPreDataLengthExceededError"
			);
			refused(node, post(node, "/" + BUCKET, ("--" + BOUNDARY + "\r\nnonsense")
				.getBytes(StandardCharsets.UTF_8)), HttpURLConnection.HTTP_BAD_REQUEST,
				"MalformedPOSTRequest"
			);
		}
	}

	/* Only a form posted to the bucket itself goes without a request signature */
	@Example
	public void authenticatesEveryOtherPost() throws Exception {
		try (S3Node node = node()) {
			final byte[] form = photo().body("photo.jpg", PHOTO);
			final String type = "multipart/form-data; boundary=" + BOUNDARY;

			final String denied = "<Code>AccessDenied</Code><Message>Access Denied</Message>";
			final String object = "/" + BUCKET + "/uploads/photo.jpg";

			for (String path : List.of(object, object + "?uploads", "/" + BUCKET + "%2Fuploads", "/",
				"/" + BUCKET + "//")) {

				final RawHttp.Answer answer = post(node.getPort(), S3Fixture.HOST, path, type, form);

				assertThat(path, answer.status(), equalTo(HttpURLConnection.HTTP_FORBIDDEN));
				assertThat(answer.body(), containsString(denied));
			}

			assertThat(post(node.getPort(), S3Fixture.HOST, "/" + BUCKET, "text/plain", form).body(),
				containsString(denied)
			);
			assertThat(objects(node), empty());
			assertThat(node.getClient().listMultipartUploads(request -> request.bucket(BUCKET)).uploads(),
				empty()
			);
		}
	}

	@Example
	public void neverDeletesForAnUnsignedForm() throws Exception {
		try (S3Node node = node()) {
			node.getClient().putObject(request -> request.bucket(BUCKET).key("a.jpg"),
				RequestBody.fromString("a")
			);

			final byte[] delete = "<Delete><Object><Key>a.jpg</Key></Object></Delete>"
				.getBytes(StandardCharsets.UTF_8);

			for (String type : List.of("multipart/form-data; boundary=" + BOUNDARY, "application/xml")) {
				final RawHttp.Answer answer = post(node.getPort(), S3Fixture.HOST, "/" + BUCKET + "?delete",
					type, delete
				);

				assertThat(type, answer.status() >= HttpURLConnection.HTTP_BAD_REQUEST, equalTo(true));
			}

			assertThat(objects(node).stream().map(S3Object::key).toList(), contains("a.jpg"));
		}
	}
}
