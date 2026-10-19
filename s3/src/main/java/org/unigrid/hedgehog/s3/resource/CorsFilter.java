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
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.s3.http.VirtualHostFilter;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.request.BucketNames;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Answers a request from a page of another origin with the headers of the CORS rule that lets it through, refusals
   included, so that the page gets to read either */
@Slf4j
@RequiredArgsConstructor
public class CorsFilter implements ContainerResponseFilter {
	private final Namespace namespace;
	private final String domain;

	/* The host names the bucket of a virtual-host request even when it was refused before its path was rewritten */
	private Optional<String> bucket(final ContainerRequestContext request) {
		final String host = request.getHeaderString(HttpHeaders.HOST);
		final Optional<String> named = Objects.isNull(domain) || Objects.isNull(host) ? Optional.empty()
			: VirtualHostFilter.subdomain(host, domain);

		return named.or(() -> request.getUriInfo().getPathSegments().stream().findFirst().map(PathSegment::getPath))
			.filter(BucketNames::valid);
	}

	/* A preflight answers for itself */
	@Override
	public void filter(final ContainerRequestContext request, final ContainerResponseContext response) {
		final String origin = request.getHeaderString(Cors.ORIGIN);
		final Optional<String> bucket = bucket(request);

		if (Objects.isNull(origin) || HttpMethod.OPTIONS.equals(request.getMethod()) || bucket.isEmpty()) {
			return;
		}

		try {
			Cors.rules(namespace, bucket.get()).flatMap(rules -> Cors.match(rules, origin, request.getMethod(),
				List.of())).ifPresent(rule -> Cors.allow(rule, origin, response.getHeaders()::putSingle));
		} catch (IOException | BlobStoreException ex) {
			log.warn("Failed to read the CORS rules of bucket {}, so a response to {} goes without them",
				bucket.get(), origin, ex
			);
		}
	}
}
