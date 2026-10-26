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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.spi.CredentialStore;

/*
   The policy a browser form upload is signed by in place of the request. The signature is the SigV4 HMAC of the
   policy as the form carries it, in base64, under the signing key of the credential scope. The policy says until
   when the form may be posted and what each of its fields may hold, and every field has to be named by one of its
   conditions. What it leaves to the upload is the length the file may have.
*/
public record PostPolicy(long minimumLength, long maximumLength) {
	public static final String KEY = "key";

	private static final String POLICY = "policy";
	private static final String ALGORITHM = "x-amz-algorithm";
	private static final String CREDENTIAL = "x-amz-credential";
	private static final String DATE = "x-amz-date";
	private static final String SIGNATURE = "x-amz-signature";
	private static final String BUCKET = "bucket";
	private static final String IGNORED = "x-ignore-";
	private static final String FIELD = "$";
	private static final String EQUALS = "eq";
	private static final String STARTS_WITH = "starts-with";
	private static final String LENGTH_RANGE = "content-length-range";
	private static final String REFUSED = "Invalid according to Policy: ";
	private static final Set<String> OPERATORS = Set.of(EQUALS, STARTS_WITH);
	private static final Set<String> UNCONDITIONED = Set.of(POLICY, SIGNATURE, "file");
	private static final ObjectMapper JSON = new ObjectMapper();

	private record Condition(String field, boolean prefix, String expected) {
		private boolean holds(final String value) {
			return prefix ? value.startsWith(expected) : value.equals(expected);
		}
	}

	/* Checks the signature before anything of the policy is read, so an unsigned policy is never even parsed */
	public static PostPolicy verify(final Map<String, String> fields, final String bucket,
		final CredentialStore credentials, final Instant now) {

		final Map<String, String> form = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		form.putAll(fields);

		final String policy = Optional.ofNullable(form.get(POLICY))
			.orElseThrow(() -> new S3Exception(S3ErrorCode.AccessDenied));

		checkSignature(form, policy, credentials);
		return evaluate(document(policy), form, bucket, now);
	}

	private static void checkSignature(final Map<String, String> form, final String policy,
		final CredentialStore credentials) {

		if (!SigV4.ALGORITHM.equals(required(form, ALGORITHM))) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Only " + SigV4.ALGORITHM + " is supported");
		}

		final Credential credential = Credential.parse(required(form, CREDENTIAL), S3ErrorCode.InvalidArgument);
		final String secretKey = credentials.secretKey(credential.accessKeyId())
			.orElseThrow(() -> new S3Exception(S3ErrorCode.InvalidAccessKeyId));

		checkDate(required(form, DATE), credential);

		if (!SigV4.matches(SigV4.sign(credential.signingKey(secretKey), policy), required(form, SIGNATURE))) {
			throw new S3Exception(S3ErrorCode.SignatureDoesNotMatch);
		}
	}

	/* The signing key is derived for the day of the scope, so that day has to be the day the form was signed */
	private static void checkDate(final String date, final Credential credential) {
		try {
			SigV4.TIMESTAMP.parse(date);
		} catch (DateTimeParseException ex) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "X-Amz-Date must be of the form "
				+ "yyyyMMdd'T'HHmmss'Z'"
			);
		}

		if (!date.startsWith(credential.date())) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Invalid credential date. Date is not the same "
				+ "as X-Amz-Date."
			);
		}
	}

	private static String required(final Map<String, String> form, final String name) {
		return Optional.ofNullable(form.get(name)).orElseThrow(() -> new S3Exception(
			S3ErrorCode.InvalidArgument, "Bucket POST must contain a field named '" + name + "'. If it is "
			+ "specified, please check the order of the fields."
		));
	}

	private static JsonNode document(final String policy) {
		try {
			return JSON.readTree(Base64.getMimeDecoder().decode(policy));
		} catch (IOException | IllegalArgumentException ex) {
			throw invalid("Invalid Policy: Invalid JSON.");
		}
	}

	private static PostPolicy evaluate(final JsonNode policy, final Map<String, String> form, final String bucket,
		final Instant now) {

		final Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		final Set<String> covered = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		PostPolicy length = new PostPolicy(0, Long.MAX_VALUE);

		checkExpiration(policy.path("expiration"), now);
		values.putAll(form);
		values.put(BUCKET, bucket);

		for (JsonNode condition : conditions(policy)) {
			if (isLengthRange(condition)) {
				length = new PostPolicy(Math.max(length.minimumLength, bound(condition.get(1), condition)),
					Math.min(length.maximumLength, bound(condition.get(2), condition))
				);
			} else {
				covered.add(check(condition, values));
			}
		}

		/* A policy handed out for one bucket would otherwise upload to every other */
		if (!covered.contains(BUCKET)) {
			throw failed("[\"eq\", \"$" + BUCKET + "\", \"" + bucket + "\"]");
		}

		refuseUnconditioned(form, covered);
		return length;
	}

	private static void checkExpiration(final JsonNode expiration, final Instant now) {
		final Instant expires;

		try {
			expires = Instant.parse(expiration.asText());
		} catch (DateTimeParseException ex) {
			throw invalid("Invalid Policy: Invalid 'expiration' value: '" + expiration.asText() + "'");
		}

		if (!now.isBefore(expires)) {
			throw new S3Exception(S3ErrorCode.AccessDenied, REFUSED + "Policy expired.");
		}
	}

	private static JsonNode conditions(final JsonNode policy) {
		final JsonNode conditions = policy.path("conditions");

		if (!conditions.isArray()) {
			throw invalid("Invalid Policy: Invalid 'conditions' value.");
		}

		return conditions;
	}

	private static boolean isLengthRange(final JsonNode condition) {
		return condition.isArray() && condition.size() == 3
			&& LENGTH_RANGE.equalsIgnoreCase(condition.get(0).asText());
	}

	private static long bound(final JsonNode bound, final JsonNode condition) {
		if (!bound.canConvertToExactIntegral() || !bound.canConvertToLong() || bound.asLong() < 0) {
			throw invalidCondition(condition);
		}

		return bound.asLong();
	}

	/* Answers the field the condition names once the field holds what the condition allows. The bucket has to be
	   named exactly, never by a prefix many buckets could share. */
	private static String check(final JsonNode condition, final Map<String, String> values) {
		final Condition parsed = parse(condition);

		if (parsed.prefix() && BUCKET.equalsIgnoreCase(parsed.field())
			|| !parsed.holds(Objects.requireNonNullElse(values.get(parsed.field()), ""))) {

			throw failed(condition.toString());
		}

		return parsed.field();
	}

	private static S3Exception failed(final String condition) {
		return new S3Exception(S3ErrorCode.AccessDenied, REFUSED + "Policy Condition failed: " + condition);
	}

	/* Either {"field": "value"}, or [operator, "$field", "value"] with an operator of eq or starts-with */
	private static Condition parse(final JsonNode condition) {
		if (condition.isObject() && condition.size() == 1) {
			final Map.Entry<String, JsonNode> exact = condition.properties().iterator().next();

			return new Condition(exact.getKey(), false, text(exact.getValue(), condition));
		}

		final String operator = condition.path(0).asText().toLowerCase(Locale.ROOT);
		final String field = text(condition.path(1), condition);

		if (!isOperation(condition, operator, field)) {
			throw invalidCondition(condition);
		}

		return new Condition(field.substring(FIELD.length()), STARTS_WITH.equals(operator),
			text(condition.get(2), condition)
		);
	}

	private static boolean isOperation(final JsonNode condition, final String operator, final String field) {
		return condition.isArray() && condition.size() == 3 && OPERATORS.contains(operator)
			&& field.startsWith(FIELD);
	}

	private static String text(final JsonNode value, final JsonNode condition) {
		if (!value.isTextual()) {
			throw invalidCondition(condition);
		}

		return value.asText();
	}

	private static void refuseUnconditioned(final Map<String, String> form, final Set<String> covered) {
		for (String name : form.keySet()) {
			final String field = name.toLowerCase(Locale.ROOT);

			if (!UNCONDITIONED.contains(field) && !field.startsWith(IGNORED) && !covered.contains(field)) {
				throw new S3Exception(S3ErrorCode.AccessDenied, REFUSED + "Extra input fields: " + name);
			}
		}
	}

	private static S3Exception invalidCondition(final JsonNode condition) {
		return invalid("Invalid Policy: Invalid Simple-Condition: " + condition);
	}

	private static S3Exception invalid(final String message) {
		return new S3Exception(S3ErrorCode.InvalidPolicyDocument, message);
	}
}
