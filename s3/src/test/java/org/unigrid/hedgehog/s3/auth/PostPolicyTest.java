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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.StringLength;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;
import org.unigrid.hedgehog.s3.spi.CredentialStore;

/* Checked against the browser upload example of the AWS documentation, "Example: Browser-Based Upload using HTTP
   POST (Using AWS Signature Version 4)", whose policy, credentials and signature are kept here as published */
public class PostPolicyTest {
	private static final String ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
	private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
	private static final String BUCKET = "sigv4examplebucket";
	private static final String DATE = "20151229T000000Z";
	private static final String CREDENTIAL = ACCESS_KEY + "/20151229/us-east-1/s3/aws4_request";
	private static final Instant BEFORE_EXPIRY = Instant.parse("2015-12-29T12:00:00Z");
	private static final CredentialStore CREDENTIALS = key -> ACCESS_KEY.equals(key) ? Optional.of(SECRET_KEY)
		: Optional.empty();

	private static final String EXAMPLE_POLICY = "eyAiZXhwaXJhdGlvbiI6ICIyMDE1LTEyLTMwVDEyOjAwOjAwLjAwMFoiLA0KICAiY29u"
		+ "ZGl0aW9ucyI6IFsNCiAgICB7ImJ1Y2tldCI6ICJzaWd2NGV4YW1wbGVidWNrZXQifSwNCiAgICBbInN0YXJ0cy13aXRoIiwgIiRrZXki"
		+ "LCAidXNlci91c2VyMS8iXSwNCiAgICB7ImFjbCI6ICJwdWJsaWMtcmVhZCJ9LA0KICAgIHsic3VjY2Vzc19hY3Rpb25fcmVkaXJlY3Qi"
		+ "OiAiaHR0cDovL3NpZ3Y0ZXhhbXBsZWJ1Y2tldC5zMy5hbWF6b25hd3MuY29tL3N1Y2Nlc3NmdWxfdXBsb2FkLmh0bWwifSwNCiAgICBb"
		+ "InN0YXJ0cy13aXRoIiwgIiRDb250ZW50LVR5cGUiLCAiaW1hZ2UvIl0sDQogICAgeyJ4LWFtei1tZXRhLXV1aWQiOiAiMTQzNjUxMjM2"
		+ "NTEyNzQifSwNCiAgICB7IngtYW16LXNlcnZlci1zaWRlLWVuY3J5cHRpb24iOiAiQUVTMjU2In0sDQogICAgWyJzdGFydHMtd2l0aCIs"
		+ "ICIkeC1hbXotbWV0YS10YWciLCAiIl0sDQoNCiAgICB7IngtYW16LWNyZWRlbnRpYWwiOiAiQUtJQUlPU0ZPRE5ON0VYQU1QTEUvMjAx"
		+ "NTEyMjkvdXMtZWFzdC0xL3MzL2F3czRfcmVxdWVzdCJ9LA0KICAgIHsieC1hbXotYWxnb3JpdGhtIjogIkFXUzQtSE1BQy1TSEEyNTYi"
		+ "fSwNCiAgICB7IngtYW16LWRhdGUiOiAiMjAxNTEyMjlUMDAwMDAwWiIgfQ0KICBdDQp9";
	private static final String EXAMPLE_SIGNATURE = "8afdbf4008c03f22c2cd3cdb72e4afbb1f6a588f3255ac628749a66d7f09699e";

	/* The fields of the example form, with the file name put in its key */
	private static Map<String, String> example() {
		final Map<String, String> form = new HashMap<>(Map.of("key", "user/user1/photo.jpg", "acl", "public-read",
			"success_action_redirect", "http://sigv4examplebucket.s3.amazonaws.com/successful_upload.html",
			"Content-Type", "image/jpeg", "x-amz-meta-uuid", "14365123651274", "x-amz-server-side-encryption",
			"AES256", "X-Amz-Credential", CREDENTIAL, "X-Amz-Algorithm", SigV4.ALGORITHM, "X-Amz-Date", DATE,
			"x-amz-meta-tag", ""
		));

		form.put("Policy", EXAMPLE_POLICY);
		form.put("X-Amz-Signature", EXAMPLE_SIGNATURE);
		return form;
	}

	/* A form for a policy of the conditions given and those every policy needs, signed with the example key */
	private static Map<String, String> signed(final Map<String, String> fields, final String... conditions) {
		final String policy = Base64.getEncoder().encodeToString(("{\"expiration\": \"2015-12-30T12:00:00.000Z\", "
			+ "\"conditions\": [" + String.join(", ", conditions) + (conditions.length == 0 ? "" : ", ")
			+ "{\"x-amz-algorithm\": \"AWS4-HMAC-SHA256\"}, {\"x-amz-credential\": \"" + CREDENTIAL + "\"}, "
			+ "{\"x-amz-date\": \"" + DATE + "\"}]}").getBytes(StandardCharsets.UTF_8)
		);
		final byte[] signingKey = SigV4.signingKey(SECRET_KEY, "20151229", "us-east-1", SigV4.SERVICE);
		final Map<String, String> form = new HashMap<>(fields);

		form.putAll(Map.of("policy", policy, "x-amz-algorithm", SigV4.ALGORITHM, "x-amz-credential", CREDENTIAL,
			"x-amz-date", DATE, "x-amz-signature", SigV4.sign(signingKey, policy)
		));

		return form;
	}

	private static PostPolicy verify(final Map<String, String> form) {
		return PostPolicy.verify(form, BUCKET, CREDENTIALS, BEFORE_EXPIRY);
	}

	private static S3Exception refusal(final Map<String, String> form) {
		return Expect.assertThrows(S3Exception.class, () -> verify(form));
	}

	private static Map<String, String> with(final Map<String, String> form, final String name, final String value) {
		final Map<String, String> changed = new HashMap<>(form);

		changed.put(name, value);
		return changed;
	}

	@Example
	public void signsThePolicyOfTheDocumentationAsItWasSigned() {
		assertThat(SigV4.sign(SigV4.signingKey(SECRET_KEY, "20151229", "us-east-1", SigV4.SERVICE), EXAMPLE_POLICY),
			equalTo(EXAMPLE_SIGNATURE)
		);
	}

	@Example
	public void acceptsTheFormOfTheDocumentation() {
		assertThat(verify(example()), equalTo(new PostPolicy(0, Long.MAX_VALUE)));
	}

	@Example
	public void refusesAFormPostedAfterItsPolicyExpired() {
		final S3Exception refusal = Expect.assertThrows(S3Exception.class, () -> PostPolicy.verify(example(), BUCKET,
			CREDENTIALS, Instant.parse("2015-12-30T12:00:00Z")));

		assertThat(refusal.getCode(), equalTo(S3ErrorCode.AccessDenied));
		assertThat(refusal.getMessage(), equalTo("Invalid according to Policy: Policy expired."));
	}

	@Example
	public void refusesASignatureThatIsNotOfThePolicy() {
		final String flipped = (EXAMPLE_SIGNATURE.charAt(0) == '8' ? "9" : "8") + EXAMPLE_SIGNATURE.substring(1);
		final String other = signed(Map.of()).get("policy");

		assertThat(refusal(with(example(), "X-Amz-Signature", flipped)).getCode(),
			equalTo(S3ErrorCode.SignatureDoesNotMatch)
		);
		assertThat(refusal(with(example(), "Policy", other)).getCode(), equalTo(S3ErrorCode.SignatureDoesNotMatch));
		assertThat(refusal(with(example(), "X-Amz-Credential", "AKIAOTHER/20151229/us-east-1/s3/aws4_request"))
			.getCode(), equalTo(S3ErrorCode.InvalidAccessKeyId)
		);
	}

	@Example
	public void refusesACredentialScopedToAnotherDayThanTheForm() {
		final S3Exception refusal = refusal(with(example(), "X-Amz-Date", "20151230T000000Z"));

		assertThat(refusal.getCode(), equalTo(S3ErrorCode.InvalidArgument));
		assertThat(refusal.getMessage(), containsString("Date is not the same as X-Amz-Date"));
		assertThat(refusal(with(example(), "X-Amz-Algorithm", "AWS4-HMAC-SHA1")).getCode(),
			equalTo(S3ErrorCode.InvalidArgument)
		);
	}

	@Example
	public void refusesAFormLackingWhatItIsSignedWith() {
		final Map<String, String> unsigned = example();

		unsigned.remove("Policy");
		assertThat(refusal(unsigned).getCode(), equalTo(S3ErrorCode.AccessDenied));

		for (String field : new String[] {"X-Amz-Signature", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Algorithm"}) {
			final Map<String, String> form = example();

			form.remove(field);
			assertThat(field, refusal(form).getMessage(), containsString("must contain a field named '"
				+ field.toLowerCase() + "'")
			);
		}
	}

	@Example
	public void refusesAFieldTheConditionsDoNotAllow() {
		final S3Exception key = refusal(with(example(), "key", "user/user2/photo.jpg"));
		final S3Exception bucket = Expect.assertThrows(S3Exception.class, () -> PostPolicy.verify(example(),
			"otherbucket", CREDENTIALS, BEFORE_EXPIRY));

		assertThat(key.getCode(), equalTo(S3ErrorCode.AccessDenied));
		assertThat(key.getMessage(), equalTo("Invalid according to Policy: Policy Condition failed: "
			+ "[\"starts-with\",\"$key\",\"user/user1/\"]")
		);
		assertThat(bucket.getMessage(), containsString("Policy Condition failed: {\"bucket\":\"" + BUCKET + "\"}"));
		assertThat(refusal(with(example(), "x-amz-meta-uuid", "1")).getCode(), equalTo(S3ErrorCode.AccessDenied));
	}

	@Example
	public void refusesAFieldNoConditionCovers() {
		final S3Exception refusal = refusal(with(example(), "x-amz-meta-extra", "1"));

		assertThat(refusal.getCode(), equalTo(S3ErrorCode.AccessDenied));
		assertThat(refusal.getMessage(), equalTo("Invalid according to Policy: Extra input fields: "
			+ "x-amz-meta-extra")
		);
		assertThat(verify(with(example(), "x-ignore-submit", "Upload")), equalTo(new PostPolicy(0, Long.MAX_VALUE)));
	}

	@Property
	public void allowsWhatStartsWithThePrefixOfACondition(@ForAll @AlphaChars @StringLength(max = 4) final String prefix,
		@ForAll @AlphaChars @StringLength(max = 6) final String key) {

		final Map<String, String> form = signed(Map.of("key", key), "[\"starts-with\", \"$key\", \"" + prefix
			+ "\"]"
		);

		if (key.startsWith(prefix)) {
			assertThat(verify(form), equalTo(new PostPolicy(0, Long.MAX_VALUE)));
		} else {
			assertThat(refusal(form).getCode(), equalTo(S3ErrorCode.AccessDenied));
		}
	}

	@Property
	public void narrowsTheLengthToEveryRangeItNames(@ForAll @LongRange(max = 1000) final long first,
		@ForAll @LongRange(max = 1000) final long second) {

		assertThat(verify(signed(Map.of(), "[\"content-length-range\", " + first + ", 1000]",
			"[\"content-length-range\", 0, " + second + "]", "[\"eq\", \"$bucket\", \"" + BUCKET + "\"]")),
			equalTo(new PostPolicy(first, second))
		);
	}

	@Example
	public void refusesAPolicyThatIsNoPolicy() {
		for (String condition : new String[] {"[\"matches\", \"$key\", \"a\"]", "[\"eq\", \"key\", \"a\"]",
			"{\"key\": 1}", "{\"key\": \"a\", \"acl\": \"private\"}", "[\"content-length-range\", -1, 10]",
			"1"}) {

			assertThat(condition, refusal(signed(Map.of("key", "a"), condition)).getCode(),
				equalTo(S3ErrorCode.InvalidPolicyDocument)
			);
		}
	}
}
