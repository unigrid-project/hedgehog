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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.jqwik.Expect;

public class AuthorizationTest {
	private static final Instant SIGNED = Instant.parse("2013-05-24T00:00:00Z");
	private static final Map<String, String> PRESIGNED = Map.of(
		"X-Amz-Algorithm", "AWS4-HMAC-SHA256",
		"X-Amz-Credential", "AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request",
		"X-Amz-Date", "20130524T000000Z",
		"X-Amz-Expires", "86400",
		"X-Amz-SignedHeaders", "host",
		"X-Amz-Signature", "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
	);

	private static Authorization presigned(final Map<String, String> query) {
		return Authorization.fromQuery(query::get, name -> null);
	}

	private static S3ErrorCode refusal(final Runnable action) {
		return Expect.assertThrows(S3Exception.class, action::run).getCode();
	}

	private static Map<String, String> without(final String name) {
		final Map<String, String> query = new HashMap<>(PRESIGNED);

		query.remove(name);
		return query;
	}

	private static Map<String, String> with(final String name, final String value) {
		final Map<String, String> query = new HashMap<>(PRESIGNED);

		query.put(name, value);
		return query;
	}

	@Example
	public void readsAPresignedQuery() {
		final Authorization authorization = presigned(PRESIGNED);

		assertThat(authorization.credential(), equalTo(new Credential("AKIAIOSFODNN7EXAMPLE", "20130524",
			"us-east-1"
		)));
		assertThat(authorization.signedHeaders(), equalTo(List.of("host")));
		assertThat(authorization.payloadHash(), equalTo("UNSIGNED-PAYLOAD"));
		assertThat(authorization.expiry(), equalTo(Optional.of(Duration.ofDays(1))));
		assertThat(authorization.excludedQuery(), equalTo(Set.of("X-Amz-Signature")));
	}

	@Example
	public void refusesAnIncompletePresignedQuery() {
		for (String name : PRESIGNED.keySet()) {
			assertThat(name, refusal(() -> presigned(without(name))),
				equalTo(S3ErrorCode.AuthorizationQueryParametersError)
			);
		}
	}

	@Example
	public void refusesAnExpiryOfMoreThanAWeekOrNone() {
		for (String expiry : new String[] { "0", "-1", "604801", "never" }) {
			assertThat(refusal(() -> presigned(with("X-Amz-Expires", expiry))),
				equalTo(S3ErrorCode.AuthorizationQueryParametersError)
			);
		}
	}

	@Example
	public void refusesAnotherAlgorithmOrDate() {
		assertThat(refusal(() -> presigned(with("X-Amz-Algorithm", "AWS4-ECDSA-P256-SHA256"))),
			equalTo(S3ErrorCode.AuthorizationQueryParametersError)
		);
		assertThat(refusal(() -> presigned(with("X-Amz-Date", "20130525T000000Z"))),
			equalTo(S3ErrorCode.AuthorizationQueryParametersError)
		);
	}

	@Example
	public void refusesAScopeThatDoesNotNameTheDayAndService() {
		final String[] scopes = { "AKID//us-east-1/s3/aws4_request", "AKID/2013/us-east-1/s3/aws4_request",
			"AKID/201305/us-east-1/s3/aws4_request", "AKID/20130525/us-east-1/s3/aws4_request",
			"AKID/2013052x/us-east-1/s3/aws4_request", "AKID/201305240/us-east-1/s3/aws4_request",
			"AKID/20130524//s3/aws4_request", "AKID/20130524/ /s3/aws4_request", "AKID/20130524/us-east-1/s4/aws4_request",
			"AKID/20130524/us-east-1/S3/aws4_request", "AKID/20130524/us-east-1/s3/aws4_requests",
			"AKID/20130524/us-east-1/s3", "AKID/20130524/us-east-1/s3/aws4_request/more"
		};

		for (String scope : scopes) {
			assertThat(scope, refusal(() -> presigned(with("X-Amz-Credential", scope))),
				equalTo(S3ErrorCode.AuthorizationQueryParametersError)
			);
			assertThat(scope, refusal(() -> Authorization.fromHeader("AWS4-HMAC-SHA256 Credential=" + scope
				+ ", SignedHeaders=host, Signature=00", Map.of("x-amz-date", "20130524T000000Z",
				"x-amz-content-sha256", "UNSIGNED-PAYLOAD")::get)), equalTo(S3ErrorCode.AuthorizationHeaderMalformed)
			);
		}
	}

	@Example
	public void refusesASignatureDatedOtherwiseThanItsScope() {
		for (String date : new String[] { "", "2013", "20130525T000000Z" }) {
			assertThat(date, refusal(() -> Authorization.fromHeader("AWS4-HMAC-SHA256 Credential=AKID/20130524/"
				+ "us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00", Map.of("x-amz-date", date,
				"x-amz-content-sha256", "UNSIGNED-PAYLOAD")::get)), equalTo(S3ErrorCode.AuthorizationHeaderMalformed)
			);
		}
	}

	@Property
	public void acceptsAPresignedRequestUntilItExpires(@ForAll @LongRange(min = -900, max = 86400) long elapsed) {
		presigned(PRESIGNED).checkTime(SIGNED.plusSeconds(elapsed));
	}

	@Property
	public void refusesAnExpiredPresignedRequest(@ForAll @LongRange(min = 86401, max = 10_000_000) long elapsed) {
		assertThat(refusal(() -> presigned(PRESIGNED).checkTime(SIGNED.plusSeconds(elapsed))),
			equalTo(S3ErrorCode.AccessDenied)
		);
	}

	@Property
	public void refusesARequestFromTooFarAhead(@ForAll @LongRange(min = 901, max = 10_000_000) long ahead) {
		assertThat(refusal(() -> presigned(PRESIGNED).checkTime(SIGNED.minusSeconds(ahead))),
			equalTo(S3ErrorCode.RequestTimeTooSkewed)
		);
	}

	@Property
	public void refusesASignedHeaderOutsideTheSkew(@ForAll @LongRange(min = 901, max = 10_000_000) long skew,
		@ForAll boolean ahead) {

		final Authorization authorization = Authorization.fromHeader("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE"
			+ "/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=00", Map.of("x-amz-date",
			"20130524T000000Z", "x-amz-content-sha256", "UNSIGNED-PAYLOAD")::get
		);

		authorization.checkTime(SIGNED.plusSeconds(skew % 900));
		assertThat(refusal(() -> authorization.checkTime(ahead ? SIGNED.minusSeconds(skew)
			: SIGNED.plusSeconds(skew))), equalTo(S3ErrorCode.RequestTimeTooSkewed)
		);
	}
}
