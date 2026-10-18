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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.entity.Bucket;
import org.unigrid.hedgehog.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

@Path("/")
@RequiredArgsConstructor
public class ServiceResource {
	private final Namespace namespace;

	@GET
	public Response listBuckets() throws IOException, BlobStoreException {
		return Responses.xml(new ListAllMyBucketsResult(namespace.buckets().stream()
			.map(bucket -> new Bucket(bucket.created(), bucket.name())).toList(), Responses.owner()
		));
	}
}
