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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.Signature;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.s3.entity.ListAllMyBucketsResult;
import org.unigrid.hedgehog.model.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.model.s3.entity.Bucket;
import org.unigrid.hedgehog.model.s3.entity.Owner;
import org.unigrid.hedgehog.server.p2p.P2PServer;

@Path("/bucket")
public class StorageBucket {
	@Getter(AccessLevel.PROTECTED)
	private Signature signature;

	public ArrayList buckets = new ArrayList(
		Arrays.asList(
			new Bucket(new Date(), "test0"),
			new Bucket(new Date(), "test1"),
			new Bucket(new Date(), "test2")
		)
	);

	/**
	 * Creates a new S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html">Create Bucket</a>
	 */
	@Path("/{bucket}") @PUT
	@Consumes(MediaType.APPLICATION_XML)
	public Response create(@Context UriInfo uri, @PathParam("bucket") String bucket,
		CreateBucketConfiguration createBucketConfiguration) {
		System.out.println("URI " + uri.getRequestUri());

		String bucketName = uri.getRequestUri().toASCIIString().split(".")[0];
		System.out.println("Name from uri " + bucketName);

		System.out.println("Bucket name " + bucket);

		Bucket createdBucket = new Bucket(new Date(), bucketName);
		System.out.println("Bucket object " + createdBucket.toString());

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
		System.out.println("LIIIIIIIIIIIIIIIIIIIST");
		Owner owner = new Owner("Degen", "11111");

		return Response.ok().entity(new ListAllMyBucketsResult(buckets, owner)).build();
	}

	/**
	 * Deletes the S3 bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucket.html">Delete Bucket</a>
	 */
	@Path("/delete/{bucket}") @DELETE
	public Response delete(@PathParam("bucket") String bucket) {
		System.out.println("Bucket name " + bucket);
		buckets.remove(0);

		System.out.println("Buckets size " + buckets.size());

		return Response.ok().header("No content", "DElete success").build();
	}
}
