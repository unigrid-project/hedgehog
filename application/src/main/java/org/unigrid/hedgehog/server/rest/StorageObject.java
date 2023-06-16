/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.model.s3.entity.CopyObjectResult;
import org.unigrid.hedgehog.model.s3.entity.ListBucketResult;
import org.unigrid.hedgehog.model.s3.entity.NoSuchBucketException;
import org.unigrid.hedgehog.model.s3.entity.NoSuchKeyException;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.service.ObjectService;

@Path("/storage-object")
public class StorageObject extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	@CDIBridgeInject
	private ObjectService objectService;

	/**
	 * Adds an object to a bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html">Put Object</a>
	 */
	@Path("/{bucket}/{key}") @POST
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response create(@NotNull @PathParam("bucket") String bucket, @NotNull @PathParam("key") String key,
		@NotNull InputStream data) {

		try {
			objectService.put(bucket, key, data);
		} catch (NoSuchBucketException e) {
			return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
		} catch (IOException e) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
		}

		return Response.ok().build();
	}

	/**
	 * Returns some or all (up to 1,000) of the objects in a bucket with each request
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html">List Objects</a>
	 */
	@Path("/list/{bucket}") @GET
	@Produces(MediaType.APPLICATION_XML)
	public Response list(@NotNull @PathParam("bucket") String bucket, @NotNull @Context UriInfo uriInfo) {
		MultivaluedMap<String, String> params = uriInfo.getQueryParameters();

		Optional<String> prefix = Optional.ofNullable(params.getFirst("prefix")).filter(s -> !s.isEmpty());
		Optional<String> delimiter = Optional.ofNullable(params.getFirst("delimiter")).filter(s -> !s.isEmpty());
		Optional<Integer> maxKeys = Optional.ofNullable(params.getFirst("maxkeys")).map(Integer::parseInt);

		ListBucketResult result;

		try {
			result = objectService.listBucket(bucket, prefix, delimiter, maxKeys);
		} catch (NoSuchBucketException e) {
			return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
		}

		return Response.ok().entity(result).build();
	}

	/**
	 * Creates a copy of an object that is already stored in bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html">Copy Object</a>
	 */
	@Path("/{bucket}/{key}") @PUT
	@Produces(MediaType.APPLICATION_XML)
	public Response copy(@Context HttpHeaders httpHeaders, @NotNull @PathParam("bucket") String destinationBucket,
		@NotNull @PathParam("key") String destinationKey) {

		String copySource = httpHeaders.getHeaderString("x-amz-copy-source");

		if (copySource == null) {
			String errorMessage = "Request header should contain 'x-amz-copy-source'";
			return Response.status(Response.Status.BAD_REQUEST).entity(errorMessage).build();
		}

		String[] parts = copySource.split("/", 2);
		String sourceBucket = parts[0];
		String sourceKey = parts.length > 1 ? parts[1] : "";

		CopyObjectResult result;

		try {
			result = objectService.copy(sourceBucket, sourceKey, destinationBucket, destinationKey);
		} catch (NoSuchBucketException e) {
			return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
		} catch (IOException e) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
		}

		return Response.ok().entity(result).build();
	}

	/**
	 * Retrieves objects from Amazon S3
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html">Get Object</a>
	 */
	@Path("/{bucket}/{key}") @GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response get(@NotNull @PathParam("bucket") String bucket, @NotNull @PathParam("key") String key) {
		byte[] byteArray;

		try {
			byteArray = objectService.getObject(bucket, key);
		} catch (NoSuchBucketException | NoSuchKeyException e) {
			return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
		} catch (Exception e) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
		}

		return Response.ok(byteArray, "application/octet-stream").build();
	}

	/**
	 * Removes the null version (if there is one) of an object and inserts a delete marker, which becomes the latest
	 * version of the object
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html">Delete Object</a>
	 */
	@Path("/{bucket}/{key}") @DELETE
	public Response delete(@NotNull @PathParam("bucket") String bucket, @NotNull @PathParam("key") String key) {
		boolean isDeleted;

		try {
			isDeleted = objectService.delete(bucket, key);
		} catch (NoSuchKeyException e) {
			return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
		} catch (Exception e) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
		}

		if (!isDeleted) {
			return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
				.entity("Deletion has failed").build();
		}

		return Response.noContent().build();
	}
}
