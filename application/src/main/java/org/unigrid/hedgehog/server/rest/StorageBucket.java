/*
    Unigrid Hedgehog
    Copyright © 2021-2023 The Unigrid Foundation, UGD Software AB

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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;
import java.io.IOException;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.service.BucketService;

@Path("/bucket")
public class StorageBucket extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private BucketService bucketService;

	/**
	 * Creates a new S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html">Create Bucket</a>
	 */
	@Path("/{bucket}") @PUT
	@Consumes(MediaType.APPLICATION_XML)
	public Response create(@PathParam("bucket") String bucket, CreateBucketConfiguration bucketConfiguration) {
		if (bucketConfiguration == null) {
			String errorMessage = "Request should contain BucketConfiguration file";
			return Response.status(Response.Status.BAD_REQUEST).entity(errorMessage).build();
		}

		String location = bucketService.create(bucket);

		ResponseBuilder builder = Response.ok();
		builder.header("Location", "/" + location);

		return builder.build();
	}

	/**
	 * Returns a list of all buckets owned by the authenticated sender of the request.
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">List Buckets</a>
	 */
	@Path("/list") @GET
	@Produces(MediaType.APPLICATION_XML)
	public Response list() {
		return Response.ok().entity(bucketService.listBuckets()).build();
	}

	/**
	 * Deletes the S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucket.html">Delete Bucket</a>
	 */
	@Path("/{bucket}") @DELETE
	public Response delete(@PathParam("bucket") String bucket) {
		boolean isDeleted;

		try {
			isDeleted = bucketService.delete(bucket);
			if (!isDeleted) {
				return Response.status(Response.Status.NOT_FOUND).build();
			}
		} catch (IOException e) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
		}

		return Response.noContent().build();
	}
}
