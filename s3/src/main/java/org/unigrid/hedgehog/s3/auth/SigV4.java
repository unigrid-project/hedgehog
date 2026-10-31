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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.common.crypto.Digests;

/*
   AWS Signature Version 4 as S3 applies it: the path of the request is encoded once, with its slashes kept and
   without normalising it, unlike the double encoding other AWS services sign.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SigV4 {
	public static final String ALGORITHM = "AWS4-HMAC-SHA256";
	public static final String SERVICE = "s3";
	public static final String TERMINATOR = "aws4_request";
	public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		.withZone(ZoneOffset.UTC);

	private static final String HMAC = "HmacSHA256";
	private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final HexFormat HEX = HexFormat.of();

	public static String uriEncode(final String value, final boolean path) {
		final StringBuilder encoded = new StringBuilder();

		for (byte octet : value.getBytes(StandardCharsets.UTF_8)) {
			if (UNRESERVED.indexOf(octet) >= 0 || path && octet == '/') {
				encoded.append((char) octet);
			} else {
				encoded.append('%').append(HEX.withUpperCase().toHexDigits(octet));
			}
		}

		return encoded.toString();
	}

	static String canonicalUri(final String rawPath) {
		final String path = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);

		return path.isEmpty() ? "/" : uriEncode(path, true);
	}

	static String canonicalQuery(final String rawQuery, final Set<String> excluded) {
		if (rawQuery == null) {
			return "";
		}

		return Arrays.stream(rawQuery.split("&")).filter(parameter -> !parameter.isEmpty())
			.map(parameter -> Arrays.stream(parameter.split("=", 2))
				.map(part -> URLDecoder.decode(part, StandardCharsets.UTF_8)).toList())
			.filter(parameter -> !excluded.contains(parameter.getFirst()))
			.map(parameter -> List.of(uriEncode(parameter.getFirst(), false),
				uriEncode(parameter.size() > 1 ? parameter.getLast() : "", false)))
			.sorted(Comparator.<List<String>, String>comparing(List::getFirst).thenComparing(List::getLast))
			.map(parameter -> parameter.getFirst() + "=" + parameter.getLast()).collect(Collectors.joining("&"));
	}

	private static String canonicalValue(final Collection<String> values) {
		return values == null ? "" : values.stream().map(value -> WHITESPACE.matcher(value.strip()).replaceAll(" "))
			.collect(Collectors.joining(","));
	}

	public static String canonicalRequest(final String method, final String rawPath, final String rawQuery,
		final Function<String, ? extends Collection<String>> headers, final List<String> signedHeaders,
		final String payloadHash, final Set<String> excludedQuery) {

		final String canonicalHeaders = signedHeaders.stream()
			.map(name -> name + ":" + canonicalValue(headers.apply(name)) + "\n").collect(Collectors.joining());

		return String.join("\n", method, canonicalUri(rawPath), canonicalQuery(rawQuery, excludedQuery),
			canonicalHeaders, String.join(";", signedHeaders), payloadHash
		);
	}

	public static String stringToSign(final String timestamp, final String scope, final String canonicalRequest) {
		return String.join("\n", ALGORITHM, timestamp, scope,
			sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8))
		);
	}

	public static byte[] signingKey(final String secretKey, final String date, final String region,
		final String service) {

		final byte[] dateKey = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);

		return hmac(hmac(hmac(dateKey, region), service), TERMINATOR);
	}

	public static String sign(final byte[] signingKey, final String stringToSign) {
		return HEX.formatHex(hmac(signingKey, stringToSign));
	}

	/* Compares in constant time, so the time a refusal takes tells nothing about how much of a guess was right */
	public static boolean matches(final String expected, final String provided) {
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
			provided.getBytes(StandardCharsets.UTF_8)
		);
	}

	@SneakyThrows
	static byte[] hmac(final byte[] key, final String data) {
		final Mac mac = Mac.getInstance(HMAC);

		mac.init(new SecretKeySpec(key, HMAC));
		return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
	}

	public static String sha256Hex(final byte[] data) {
		return HEX.formatHex(Digests.sha256(data));
	}
}
