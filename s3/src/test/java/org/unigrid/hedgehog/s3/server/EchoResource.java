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

package org.unigrid.hedgehog.s3.server;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.unigrid.hedgehog.s3.http.Subresource;

/* Answers with what routing made of a request: the location of a bucket names it, an object holds its own path */
@Path("/{bucket}")
public class EchoResource {
	@GET
	@Produces(MediaType.APPLICATION_XML)
	public String bucket(@PathParam("bucket") String bucket, @Context UriInfo uri) {
		return "<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" + bucket + " "
			+ Subresource.of(uri.getQueryParameters()) + "</LocationConstraint>";
	}

	@GET
	@Path("{key:.+}")
	@Produces(MediaType.TEXT_PLAIN)
	public String object(@PathParam("bucket") String bucket, @PathParam("key") String key) {
		return bucket + "/" + key;
	}
}
