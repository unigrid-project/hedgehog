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

package org.unigrid.hedgehog.s3.resource;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import org.unigrid.hedgehog.s3.checksum.ChecksumAlgorithm;
import org.unigrid.hedgehog.s3.entity.CORSConfiguration;
import org.unigrid.hedgehog.s3.entity.CORSRule;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.entity.S3Xml;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Payload;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The CORS rules of a bucket, kept as the document the client sent, along with what S3 accepts of them and how S3
   answers the browsers they allow */
final class Cors {
	static final String ORIGIN = "Origin";

	private static final String LIST = ", ";
	private static final String REQUEST_METHOD = "Access-Control-Request-Method";
	private static final String REQUEST_HEADERS = "Access-Control-Request-Headers";
	private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
	private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
	private static final String ALLOW_METHODS = "Access-Control-Allow-Methods";
	private static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";
	private static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
	private static final String MAX_AGE = "Access-Control-Max-Age";
	private static final String VARY = String.join(LIST, ORIGIN, REQUEST_HEADERS, REQUEST_METHOD);
	private static final String NO_RULES = "CORSResponse: CORS is not enabled for this bucket.";

	/* Word for word what S3 answers, misspelling included */
	private static final String NOT_ALLOWED = "CORSResponse: This CORS request is not allowed. This is usually "
		+ "because the evalution of Origin, request method / Access-Control-Request-Method or "
		+ "Access-Control-Request-Headers are not whitelisted by the resource's CORS spec.";

	private static final int MAX_RULES = 100;
	private static final String WILDCARD = "*";
	private static final Set<String> METHODS = Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.POST,
		HttpMethod.DELETE, HttpMethod.HEAD
	);

	private Cors() {
		/* Static helpers only */
	}

	/* A browser asks before a request it may not send unasked, without credentials, so the rules alone answer it */
	static Response preflight(final Namespace namespace, final String name, final HttpHeaders headers)
		throws IOException, BlobStoreException, MalformedXmlException {

		final String origin = origin(headers);
		final CORSConfiguration rules = rules(namespace, name).orElseThrow(() -> forbidden(NO_RULES));
		final String method = Objects.requireNonNullElse(headers.getHeaderString(REQUEST_METHOD),
			HttpMethod.OPTIONS
		);
		final List<String> requested = names(headers.getHeaderString(REQUEST_HEADERS));
		final CORSRule rule = match(rules, origin, method, requested).orElseThrow(() -> forbidden(NOT_ALLOWED));
		final Response.ResponseBuilder response = Response.ok();

		allow(rule, origin, response::header);

		if (!requested.isEmpty()) {
			response.header(ALLOW_HEADERS, String.join(LIST, requested));
		}

		return response.build();
	}

	/* Only a bucket has rules */
	static Response preflight(final HttpHeaders headers) {
		origin(headers);
		throw forbidden(NO_RULES);
	}

	/* The rule that lets the origin send the request, given the method and every header it means to send */
	static Optional<CORSRule> match(final CORSConfiguration rules, final String origin, final String method,
		final List<String> headers) {

		return rules.getRules().stream().filter(rule -> rule.getAllowedMethods().contains(method)
			&& rule.getAllowedOrigins().stream().anyMatch(pattern -> matches(pattern, origin, false))
			&& headers.stream().allMatch(header -> rule.getAllowedHeaders().stream()
				.anyMatch(pattern -> matches(pattern, header, true)))
		).findFirst();
	}

	/* A bucket that is not there has no rules, unlike what S3 answers, so that nobody learns from a preflight which
	   buckets there are */
	static Optional<CORSConfiguration> rules(final Namespace namespace, final String name) throws IOException,
		BlobStoreException, MalformedXmlException {

		final Optional<String> document = namespace.bucket(name).map(bucket -> bucket.configurations()
			.get(Bucket.CORS));

		return document.isEmpty() ? Optional.empty() : Optional.of(S3Xml.read(CORSConfiguration.class,
			new ByteArrayInputStream(document.get().getBytes(StandardCharsets.UTF_8))
		));
	}

	/* What a preflight and the request it cleared are both answered with. A rule open to every origin is answered
	   for every origin, which S3 does in place of naming the origin and allowing it credentials. */
	static void allow(final CORSRule rule, final String origin, final BiConsumer<String, Object> header) {
		final boolean everyOrigin = rule.getAllowedOrigins().contains(WILDCARD);

		header.accept(ALLOW_ORIGIN, everyOrigin ? WILDCARD : origin);

		if (!everyOrigin) {
			header.accept(ALLOW_CREDENTIALS, true);
		}

		header.accept(HttpHeaders.VARY, VARY);
		header.accept(ALLOW_METHODS, String.join(LIST, rule.getAllowedMethods()));

		if (!rule.getExposeHeaders().isEmpty()) {
			header.accept(EXPOSE_HEADERS, String.join(LIST, rule.getExposeHeaders()));
		}

		if (Objects.nonNull(rule.getMaxAgeSeconds())) {
			header.accept(MAX_AGE, rule.getMaxAgeSeconds());
		}
	}

	private static String origin(final HttpHeaders headers) {
		return Optional.ofNullable(headers.getHeaderString(ORIGIN)).orElseThrow(() -> new S3Exception(
			S3ErrorCode.BadRequest, "Insufficient information. Origin request header needed."
		));
	}

	private static List<String> names(final String list) {
		return Objects.isNull(list) ? List.of() : Arrays.stream(list.split(",")).map(String::strip)
			.filter(name -> !name.isEmpty()).map(name -> name.toLowerCase(Locale.ROOT)).toList();
	}

	/* A pattern holds at most one wildcard, which stands for any run of characters */
	private static boolean matches(final String pattern, final String value, final boolean ignoreCase) {
		final int wildcard = pattern.indexOf(WILDCARD);

		if (wildcard < 0) {
			return value.length() == pattern.length() && value.regionMatches(ignoreCase, 0, pattern, 0,
				pattern.length()
			);
		}

		final int suffix = pattern.length() - wildcard - 1;

		return value.length() >= wildcard + suffix && value.regionMatches(ignoreCase, 0, pattern, 0, wildcard)
			&& value.regionMatches(ignoreCase, value.length() - suffix, pattern, wildcard + 1, suffix);
	}

	private static S3Exception forbidden(final String message) {
		return new S3Exception(S3ErrorCode.AccessForbidden, message);
	}

	static Response read(final Bucket bucket) {
		final String document = Optional.ofNullable(bucket.configurations().get(Bucket.CORS))
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchCORSConfiguration));

		return Response.ok(document, MediaType.APPLICATION_XML_TYPE).build();
	}

	static Response write(final Namespace namespace, final String name, final ContainerRequestContext request)
		throws IOException, BlobStoreException, NamespaceException, MalformedXmlException {

		Requests.bucket(namespace, name);
		requireDigest(request);

		final byte[] document = Requests.document(request);

		validate(S3Xml.read(CORSConfiguration.class, new ByteArrayInputStream(document)));
		namespace.updateBucket(name, bucket -> configured(bucket, new String(document, StandardCharsets.UTF_8)));
		return Response.ok().build();
	}

	static Response delete(final Namespace namespace, final String name) throws IOException, BlobStoreException,
		NamespaceException {

		namespace.updateBucket(name, bucket -> configured(bucket, null));
		return Response.noContent().build();
	}

	/* No document leaves the bucket without rules */
	private static Bucket configured(final Bucket bucket, final String document) {
		final Map<String, String> configurations = new HashMap<>(bucket.configurations());

		if (Objects.isNull(document)) {
			configurations.remove(Bucket.CORS);
		} else {
			configurations.put(Bucket.CORS, document);
		}

		return bucket.toBuilder().configurations(configurations).build();
	}

	/* S3 insists on an integrity check of the rules, which newer clients send as a checksum instead of an MD5 */
	private static void requireDigest(final ContainerRequestContext request) {
		final boolean checksum = Arrays.stream(ChecksumAlgorithm.values())
			.anyMatch(algorithm -> Objects.nonNull(request.getHeaderString(algorithm.getHeader())));

		if (!checksum && Objects.isNull(request.getHeaderString(Payload.CONTENT_MD5))
			&& Objects.isNull(request.getHeaderString(Payload.TRAILER))) {

			throw new S3Exception(S3ErrorCode.InvalidRequest, "Missing required header for this request: "
				+ Payload.CONTENT_MD5
			);
		}
	}

	private static void validate(final CORSConfiguration configuration) {
		if (configuration.getRules().isEmpty() || configuration.getRules().size() > MAX_RULES) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		configuration.getRules().forEach(Cors::validate);
	}

	private static void validate(final CORSRule rule) {
		if (rule.getAllowedOrigins().isEmpty() || rule.getAllowedMethods().isEmpty()) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		for (String method : rule.getAllowedMethods()) {
			if (!METHODS.contains(method)) {
				throw new S3Exception(S3ErrorCode.InvalidRequest, "Found unsupported HTTP method in CORS "
					+ "config. Unsupported method is " + method
				);
			}
		}

		refuseWildcards("AllowedOrigin", rule.getAllowedOrigins());
		refuseWildcards("AllowedHeader", rule.getAllowedHeaders());
	}

	private static void refuseWildcards(final String element, final List<String> patterns) {
		for (String pattern : patterns) {
			if (pattern.indexOf(WILDCARD) != pattern.lastIndexOf(WILDCARD)) {
				throw new S3Exception(S3ErrorCode.InvalidRequest, element + " \"" + pattern
					+ "\" can not have more than one wildcard."
				);
			}
		}
	}
}
