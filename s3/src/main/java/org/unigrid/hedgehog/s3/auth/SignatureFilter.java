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
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
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
	public static final String DECODED_LENGTH = "x-amz-decoded-content-length";
	private static final String AWS_CHUNKED = "aws-chunked";

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

		payload(request, PayloadMode.of(authorization.payloadHash()), authorization, signingKey).attach(request);
	}

	private static Payload payload(final ContainerRequestContext request, final PayloadMode mode,
		final Authorization authorization, final byte[] signingKey) {

		if (!mode.isChunked()) {
			final Optional<String> sha256 = mode == PayloadMode.SHA256 ? Optional.of(authorization.payloadHash())
				: Optional.empty();

			return Payload.create(request::getHeaderString, request.getEntityStream(),
				length(request.getHeaderString(HttpHeaders.CONTENT_LENGTH)), sha256, name -> Optional.empty()
			);
		}

		final long length = length(request.getHeaderString(DECODED_LENGTH))
			.orElseThrow(() -> new S3Exception(S3ErrorCode.MissingContentLength));
		final Optional<ChunkSigner> signer = mode.isSignedChunks() ? Optional.of(new ChunkSigner(signingKey,
			authorization.timestamp(), authorization.credential().scope(), authorization.signature()))
			: Optional.empty();
		final AwsChunkedInputStream body = new AwsChunkedInputStream(request.getEntityStream(), signer,
			mode.hasTrailer() ? names(request.getHeaderString(Payload.TRAILER)) : Set.of(), length
		);

		unchunk(request, length);
		return Payload.create(request::getHeaderString, body, OptionalLong.of(length), Optional.empty(),
			body::trailer
		);
	}

	private static Set<String> names(final String list) {
		return Arrays.stream(Optional.ofNullable(list).orElse("").split(",")).map(String::strip)
			.filter(name -> !name.isEmpty()).map(name -> name.toLowerCase(Locale.ROOT))
			.collect(Collectors.toSet());
	}

	/* Resources see the body as it was before the client framed it, and store its other encodings with it */
	private static void unchunk(final ContainerRequestContext request, final long length) {
		final String declared = Optional.ofNullable(request.getHeaderString(HttpHeaders.CONTENT_ENCODING))
			.orElse("");
		final String encodings = Arrays.stream(declared.split(",")).map(String::strip)
			.filter(encoding -> !encoding.isEmpty() && !AWS_CHUNKED.equalsIgnoreCase(encoding))
			.collect(Collectors.joining(","));

		request.getHeaders().putSingle(HttpHeaders.CONTENT_LENGTH, Long.toString(length));

		if (encodings.isEmpty()) {
			request.getHeaders().remove(HttpHeaders.CONTENT_ENCODING);
		} else {
			request.getHeaders().putSingle(HttpHeaders.CONTENT_ENCODING, encodings);
		}
	}
}
