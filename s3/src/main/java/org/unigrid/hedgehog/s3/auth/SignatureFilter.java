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

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Payload;
import org.unigrid.hedgehog.s3.spi.CredentialStore;

/* Pre-matching, so nothing about the routes can be learnt without a valid signature */
@PreMatching
@Priority(Priorities.AUTHENTICATION)
@RequiredArgsConstructor
public class SignatureFilter implements ContainerRequestFilter {
	private final CredentialStore credentials;
	private final Clock clock;

	private static Authorization authorization(final ContainerRequestContext request) {
		final String header = request.getHeaderString(HttpHeaders.AUTHORIZATION);
		final MultivaluedMap<String, String> query = request.getUriInfo().getQueryParameters();
		final boolean presigned = query.containsKey(Authorization.ALGORITHM_PARAMETER);

		if (header != null && presigned) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Only one auth mechanism allowed; only the "
				+ "X-Amz-Algorithm query parameter or the Authorization header should be specified"
			);
		}

		if (header != null) {
			return Authorization.fromHeader(header, request::getHeaderString);
		}

		if (presigned) {
			return Authorization.fromQuery(query::getFirst, request::getHeaderString);
		}

		throw new S3Exception(S3ErrorCode.AccessDenied);
	}

	static OptionalLong length(final String value) {
		try {
			return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseUnsignedLong(value));
		} catch (NumberFormatException ex) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "The length " + value + " is not a number");
		}
	}

	@Override
	public void filter(final ContainerRequestContext request) {
		final Authorization authorization = authorization(request);
		final Credential credential = authorization.credential();
		final String secretKey = credentials.secretKey(credential.accessKeyId())
			.orElseThrow(() -> new S3Exception(S3ErrorCode.InvalidAccessKeyId));
		final URI uri = request.getUriInfo().getRequestUri();
		final byte[] signingKey = credential.signingKey(secretKey);

		authorization.checkTime(clock.instant());
		authorization.checkSigned(request.getHeaders().keySet().stream().map(name -> name.toLowerCase(Locale.ROOT))
			.collect(Collectors.toSet())
		);

		final String canonicalRequest = SigV4.canonicalRequest(request.getMethod(), uri.getRawPath(),
			uri.getRawQuery(), request.getHeaders()::get, authorization.signedHeaders(),
			authorization.payloadHash(), authorization.excludedQuery()
		);
		final String expected = SigV4.sign(signingKey, SigV4.stringToSign(authorization.timestamp(),
			credential.scope(), canonicalRequest
		));

		if (!SigV4.matches(expected, authorization.signature())) {
			throw new S3Exception(S3ErrorCode.SignatureDoesNotMatch);
		}

		payload(request, PayloadMode.of(authorization.payloadHash()), authorization).attach(request);
	}

	private Payload payload(final ContainerRequestContext request, final PayloadMode mode,
		final Authorization authorization) {

		if (mode.isChunked()) {
			throw new S3Exception(S3ErrorCode.NotImplemented, "Chunked uploads are not supported yet");
		}

		final Optional<String> sha256 = mode == PayloadMode.SHA256 ? Optional.of(authorization.payloadHash())
			: Optional.empty();

		return Payload.create(request::getHeaderString, request.getEntityStream(),
			length(request.getHeaderString(HttpHeaders.CONTENT_LENGTH)), sha256, name -> Optional.empty()
		);
	}
}
