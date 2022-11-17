/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.server.rest;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.unigrid.hedgehog.model.Signature;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;

@Path("/bucket")
@Consumes(MediaType.APPLICATION_XML)
public class Bucket {
	Signature signature;

	/**
	 * Creates a new S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html">Create Bucket</a>
	 */
	@Path("/{bucket}") @PUT
	public Response create(@Context UriInfo uri, @PathParam("bucket") String bucket, CreateBucketConfiguration createBucketConfiguration) {
		//System.out.println(uri.getRequestUri())
		//uri.getRequestUri().toASCIIString().split(".")[0];
		return Response.ok().header("Location", "shit").build();
	}

	/**
	 * Returns a list of all buckets owned by the authenticated sender of the request.
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">List Buckets</a>
	 */
	@Path("/list") @GET
	@Produces(MediaType.APPLICATION_XML)
	public Response list() {
		return Response.ok().entity(new ListAllMyBucketsResult()).build();
	}

	/**
	 * Deletes the S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucket.html">Delete Bucket</a>
	 */
	@Path("/delete/{bucket}") @DELETE
	public Response delete(@PathParam("bucket") String bucket) {
		return Response.ok().header("No content", "success").build();
	}
}
