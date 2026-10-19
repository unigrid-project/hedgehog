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

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Optional;
import org.unigrid.hedgehog.s3.entity.LifecycleConfiguration;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.lifecycle.Lifecycle;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The lifecycle configuration of a bucket, kept as the client sent it once its rules check out */
final class Lifecycles {
	private Lifecycles() {
		/* Static helpers only */
	}

	static LifecycleConfiguration configuration(final Bucket bucket) {
		return Optional.ofNullable(bucket.configurations().get(Bucket.LIFECYCLE))
			.map(document -> Requests.kept(LifecycleConfiguration.class, document))
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchLifecycleConfiguration));
	}

	static Response configure(final Namespace namespace, final String name, final ContainerRequestContext request)
		throws IOException, BlobStoreException, NamespaceException, MalformedXmlException {

		Requests.bucket(namespace, name);

		final String document = Requests.digestedXml(request);

		Lifecycle.rules(Requests.parse(LifecycleConfiguration.class, document));
		namespace.updateBucket(name, bucket -> bucket.configured(Bucket.LIFECYCLE, document));
		return Response.ok().build();
	}

	static Response delete(final Namespace namespace, final String name) throws IOException, BlobStoreException,
		NamespaceException {

		Requests.bucket(namespace, name);
		namespace.updateBucket(name, bucket -> bucket.configured(Bucket.LIFECYCLE, null));
		return Response.noContent().build();
	}
}
