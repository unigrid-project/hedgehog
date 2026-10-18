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

package org.unigrid.hedgehog.s3.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;

/*
   Turns 'bucket.domain/key' into 'domain/bucket/key', so resources only ever see path-style requests. Runs after
   authentication, because clients sign the path they sent and not the rewritten one.
*/
@PreMatching
@Priority(Priorities.AUTHENTICATION + 1)
@RequiredArgsConstructor
public class VirtualHostFilter implements ContainerRequestFilter {
	private static final Pattern PORT = Pattern.compile(":\\d+$");
	private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]*");

	private final String domain;

	static Optional<String> bucket(final String host, final String domain) {
		final String name = PORT.matcher(host).replaceFirst("").toLowerCase(Locale.ROOT);
		final String suffix = "." + domain.toLowerCase(Locale.ROOT);

		if (!name.endsWith(suffix) || name.length() == suffix.length()) {
			return Optional.empty();
		}

		final String bucket = name.substring(0, name.length() - suffix.length());

		if (!BUCKET.matcher(bucket).matches()) {
			throw new S3Exception(S3ErrorCode.InvalidBucketName);
		}

		return Optional.of(bucket);
	}

	static URI rewrite(final URI uri, final String bucket) {
		final String path = "/".equals(uri.getRawPath()) ? "" : uri.getRawPath();
		final String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

		return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + "/" + bucket + path + query);
	}

	@Override
	public void filter(final ContainerRequestContext request) {
		final String host = request.getHeaderString(HttpHeaders.HOST);

		if (domain != null && host != null) {
			bucket(host, domain).ifPresent(bucket -> request.setRequestUri(
				rewrite(request.getUriInfo().getRequestUri(), bucket)
			));
		}
	}
}
