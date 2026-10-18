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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

/* The CORS rules of a bucket, kept as the document the client sent, along with what S3 accepts of them */
final class Cors {
	private static final int MAX_RULES = 100;
	private static final String WILDCARD = "*";
	private static final Set<String> METHODS = Set.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.POST,
		HttpMethod.DELETE, HttpMethod.HEAD
	);

	private Cors() {
		/* Static helpers only */
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
