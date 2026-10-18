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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.unigrid.hedgehog.s3.checksum.ChecksumAlgorithm;
import org.unigrid.hedgehog.s3.entity.Owner;
import org.unigrid.hedgehog.s3.index.ObjectVersion;

final class Responses {
	static final String VERSION_ID = "x-amz-version-id";
	static final String DELETE_MARKER = "x-amz-delete-marker";

	/* Every bucket and object of a node belongs to the one account its keys share */
	private static final String OWNER = "hedgehog";

	private Responses() {
		/* Static helpers only */
	}

	static Owner owner() {
		return new Owner(OWNER, OWNER);
	}

	static Response xml(final Object document) {
		return Response.ok(document, MediaType.APPLICATION_XML_TYPE).build();
	}

	static String quoted(final String etag) {
		return "\"" + etag + "\"";
	}

	/* Only a bucket that ever had versioning enabled hands out version ids other than the null one */
	static Response.ResponseBuilder versioned(final Response.ResponseBuilder response, final ObjectVersion version) {
		return version.isNullVersion() ? response : response.header(VERSION_ID, version.versionId());
	}

	static Response.ResponseBuilder checksums(final Response.ResponseBuilder response,
		final Map<String, String> checksums) {

		checksums.forEach((algorithm, value) -> response.header(ChecksumAlgorithm.valueOf(algorithm).getHeader(),
			value
		));

		return response;
	}
}
