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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.utils.http.SdkHttpUtils;

public class SigV4OracleTest {
	private static final String ACCESS_KEY = "HKORACLE000000000000";
	private static final Pattern AUTHORIZATION = Pattern.compile(
		"AWS4-HMAC-SHA256 Credential=\\w+/(\\d{8})/([\\w-]+)/s3/aws4_request, SignedHeaders=([^,]+), "
		+ "Signature=(\\p{XDigit}{64})"
	);

	@Provide
	public Arbitrary<String> provideText() {
		return Arbitraries.strings().withCharRange(' ', '~').withChars("åäö€ሴ\t").ofMaxLength(30);
	}

	/* The SDK trims the keys it is given, and access keys never hold whitespace anyway */
	@Provide
	public Arbitrary<String> provideSecret() {
		return Arbitraries.strings().alpha().numeric().withChars("+/").ofMinLength(1).ofMaxLength(40);
	}

	@Provide
	public Arbitrary<Map<String, String>> provideQuery() {
		return Arbitraries.maps(provideText().filter(name -> !name.isEmpty()), provideText()).ofMaxSize(5);
	}

	@Provide
	public Arbitrary<Map<String, String>> provideHeaders() {
		return Arbitraries.maps(Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10)
			.map(name -> "x-amz-meta-" + name), provideText().map(String::strip)).ofMaxSize(4);
	}

	@Property(tries = 300)
	public void signsWhatTheSdkSigns(@ForAll SdkHttpMethod method, @ForAll("provideText") String key,
		@ForAll("provideQuery") Map<String, String> query, @ForAll("provideHeaders") Map<String, String> headers,
		@ForAll @Size(max = 64) byte[] body, @ForAll("provideSecret") String secret) {

		final SdkHttpRequest.Builder request = SdkHttpRequest.builder().protocol("http").host("localhost")
			.port(52885).method(method).encodedPath("/bucket/" + SdkHttpUtils.urlEncodeIgnoreSlashes(key))
			.putHeader("x-amz-content-sha256", SigV4.sha256Hex(body));

		query.forEach(request::putRawQueryParameter);
		headers.forEach(request::putHeader);

		final SdkHttpRequest signed = SdkSigner.sign(request.build(), body, ACCESS_KEY, secret,
			Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)
		);
		final Matcher authorization = AUTHORIZATION.matcher(signed.firstMatchingHeader("Authorization").orElseThrow());

		assertThat(authorization.matches(), equalTo(true));

		final String canonical = SigV4.canonicalRequest(method.name(), signed.encodedPath(),
			SdkHttpUtils.encodeAndFlattenQueryParameters(signed.rawQueryParameters()).orElse(null),
			signed.headers()::get, List.of(authorization.group(3).split(";")), SigV4.sha256Hex(body), Set.of()
		);
		final String stringToSign = SigV4.stringToSign(signed.firstMatchingHeader("X-Amz-Date").orElseThrow(),
			authorization.group(1) + "/" + authorization.group(2) + "/s3/aws4_request", canonical
		);

		assertThat(SigV4.sign(SigV4.signingKey(secret, authorization.group(1), authorization.group(2), SigV4.SERVICE),
			stringToSign), equalTo(authorization.group(4))
		);
	}
}
