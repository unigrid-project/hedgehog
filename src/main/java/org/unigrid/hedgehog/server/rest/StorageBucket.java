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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.unigrid.hedgehog.model.Signature;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.service.BucketService;

@Path("/bucket")
public class StorageBucket extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	private Signature signature;

	private final BucketService bucketService = new BucketService();

	/**
	 * Creates a new S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html">Create Bucket</a>
	 */
	@PUT
	@Consumes(MediaType.APPLICATION_XML)
	public Response create(@Context UriInfo uri, CreateBucketConfiguration bucketConfiguration) {
		if (bucketConfiguration == null) {
			String errorMessage = "Request should contain BucketConfiguration file";
			return Response.status(Response.Status.BAD_REQUEST).entity(errorMessage).build();			
		}

		String url = uri.getRequestUri().toASCIIString();
		System.out.println("URI " + url);

		String bucketName = url.split("\\.")[0];
		System.out.println("Base from uri " + bucketName);

		Bucket createdBucket = bucketService.create("TestBUCKET");
		System.out.println("Bucket object " + createdBucket.toString());

		return Response.ok().header("Location", bucketName).build();
	}

	/**
	 * Returns a list of all buckets owned by the authenticated sender of the request.
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListBuckets.html">List Buckets</a>
	 */
	@Path("/list") @GET
	@Produces(MediaType.APPLICATION_XML)
	public Response list() {
		return Response.ok().entity(bucketService.getAll()).build();
	}

	/**
	 * Deletes the S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucket.html">Delete Bucket</a>
	 */
	@Path("/delete") @DELETE
	public Response delete(@Context UriInfo uri) {

		String url = uri.getRequestUri().toASCIIString();
		System.out.println("URI " + url);

		String bucketName = url.split("\\.")[0];
		System.out.println("Base from uri " + bucketName);

		boolean isDeleted = bucketService.delete("test1");

		System.out.println("Has been deleted " + isDeleted);

		if (!isDeleted) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}

		return Response.noContent().build();
	}
}
