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

import jakarta.ws.rs.core.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/* What a request says about its own signature, before anything of it is trusted */
record Authorization(Credential credential, List<String> signedHeaders, String signature, String timestamp,
	String payloadHash, Optional<Duration> expiry) {

	static final String CONTENT_SHA256 = "x-amz-content-sha256";
	static final String DATE = "x-amz-date";
	static final String ALGORITHM_PARAMETER = "X-Amz-Algorithm";
	static final String SIGNATURE_PARAMETER = "X-Amz-Signature";
	static final Duration MAX_SKEW = Duration.ofMinutes(15);
	static final Duration MAX_EXPIRY = Duration.ofDays(7);

	private static final String UNSIGNED = "UNSIGNED-PAYLOAD";

	private static final String PREFIX = SigV4.ALGORITHM + " ";
	private static final String HOST = "host";

	static Authorization fromHeader(final String header, final Function<String, String> headers) {
		final S3ErrorCode malformed = S3ErrorCode.AuthorizationHeaderMalformed;

		if (!header.startsWith(PREFIX)) {
			throw new S3Exception(malformed, "Only " + SigV4.ALGORITHM + " is supported");
		}

		final Map<String, String> fields = Arrays.stream(header.substring(PREFIX.length()).split(","))
			.map(field -> field.strip().split("=", 2)).filter(field -> field.length == 2)
			.collect(Collectors.toMap(field -> field[0], field -> field[1], (first, second) -> second));
		final String payloadHash = Optional.ofNullable(headers.apply(CONTENT_SHA256)).orElseThrow(() ->
			new S3Exception(S3ErrorCode.InvalidRequest, "Missing required header for this request: "
				+ CONTENT_SHA256)
		);

		return new Authorization(Credential.parse(field(fields, "Credential", malformed), malformed),
			signedHeaders(field(fields, "SignedHeaders", malformed), malformed),
			field(fields, "Signature", malformed), timestamp(headers), payloadHash, Optional.empty()
		).dated(malformed);
	}

	static Authorization fromQuery(final Function<String, String> query, final Function<String, String> headers) {
		final S3ErrorCode malformed = S3ErrorCode.AuthorizationQueryParametersError;
		final Function<String, String> parameter = name -> Optional.ofNullable(query.apply(name))
			.orElseThrow(() -> new S3Exception(malformed, "Query-string authentication requires the " + name
				+ " parameter"
			));

		if (!SigV4.ALGORITHM.equals(parameter.apply(ALGORITHM_PARAMETER))) {
			throw new S3Exception(malformed, ALGORITHM_PARAMETER + " only supports " + SigV4.ALGORITHM);
		}

		return new Authorization(Credential.parse(parameter.apply("X-Amz-Credential"), malformed),
			signedHeaders(parameter.apply("X-Amz-SignedHeaders"), malformed),
			parameter.apply(SIGNATURE_PARAMETER), parameter.apply("X-Amz-Date"),
			Optional.ofNullable(headers.apply(CONTENT_SHA256)).orElse(UNSIGNED),
			Optional.of(expiry(parameter.apply("X-Amz-Expires")))
		).dated(malformed);
	}

	private static Duration expiry(final String seconds) {
		final S3Exception invalid = new S3Exception(S3ErrorCode.AuthorizationQueryParametersError,
			"X-Amz-Expires must be a number of seconds from 1 to " + MAX_EXPIRY.toSeconds()
		);

		try {
			final Duration expiry = Duration.ofSeconds(Long.parseLong(seconds));

			if (expiry.isNegative() || expiry.isZero() || expiry.compareTo(MAX_EXPIRY) > 0) {
				throw invalid;
			}

			return expiry;
		} catch (NumberFormatException ex) {
			throw invalid;
		}
	}

	Authorization dated(final S3ErrorCode malformed) {
		if (!timestamp.startsWith(credential.date())) {
			throw new S3Exception(malformed, "Invalid credential date. Date is not the same as X-Amz-Date.");
		}

		return this;
	}

	static String field(final Map<String, String> fields, final String name, final S3ErrorCode malformed) {
		return Optional.ofNullable(fields.get(name)).orElseThrow(() -> new S3Exception(malformed, "The "
			+ name + " of the signature is missing"
		));
	}

	static List<String> signedHeaders(final String value, final S3ErrorCode malformed) {
		final List<String> names = List.of(value.split(";"));

		if (!names.contains(HOST)) {
			throw new S3Exception(malformed, "The host header must be signed");
		}

		return names;
	}

	private static String timestamp(final Function<String, String> headers) {
		final String amzDate = headers.apply(DATE);
		final String httpDate = headers.apply(HttpHeaders.DATE);

		if (amzDate != null) {
			return amzDate;
		}

		if (httpDate != null) {
			return SigV4.TIMESTAMP.format(parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME));
		}

		throw undated();
	}

	private static Instant parse(final String time, final DateTimeFormatter format) {
		try {
			return format.parse(time, Instant::from);
		} catch (DateTimeParseException ex) {
			throw undated();
		}
	}

	private static S3Exception undated() {
		return new S3Exception(S3ErrorCode.AccessDenied, "AWS authentication requires a valid Date or x-amz-date "
			+ "header"
		);
	}

	void checkTime(final Instant now) {
		final Instant signed = parse(timestamp, SigV4.TIMESTAMP);

		if (signed.isAfter(now.plus(MAX_SKEW)) || expiry.isEmpty() && signed.isBefore(now.minus(MAX_SKEW))) {
			throw new S3Exception(S3ErrorCode.RequestTimeTooSkewed);
		}

		if (expiry.isPresent() && now.isAfter(signed.plus(expiry.get()))) {
			throw new S3Exception(S3ErrorCode.AccessDenied, "Request has expired");
		}
	}

	/* An x-amz header left out of the signature could have been added or changed by anyone on the way */
	void checkSigned(final Set<String> headers) {
		if (headers.stream().anyMatch(name -> name.startsWith("x-amz-") && !signedHeaders.contains(name))) {
			throw new S3Exception(S3ErrorCode.AccessDenied, "There were headers present in the request which "
				+ "were not signed"
			);
		}
	}

	/* A presigned request carries its own signature in the query it signs */
	Set<String> excludedQuery() {
		return expiry.isPresent() ? Set.of(SIGNATURE_PARAMETER) : Set.of();
	}
}
