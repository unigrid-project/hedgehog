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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.io.InputStream;
import org.unigrid.hedgehog.model.Signature;
import org.unigrid.hedgehog.model.cdi.CDIBridgeInject;
import org.unigrid.hedgehog.model.cdi.CDIBridgeResource;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.service.ObjectService;

@Path("/storage-object")
public class StorageObject extends CDIBridgeResource {
	@CDIBridgeInject
	private P2PServer p2pServer;

	private Signature signature;

	private final ObjectService objectService = new ObjectService();

	/**
	 * Adds an object to a bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html">Put Object</a>
	 */
	@Path("/{key}") @PUT
	@Consumes(MediaType.APPLICATION_OCTET_STREAM)
	public Response create(@Context UriInfo uri, @PathParam("key") String key, InputStream data) throws IOException {
		System.out.println("Key: " + key);

		String url = uri.getRequestUri().toASCIIString();
		System.out.println("URI " + url);

		String bucketName = url.split("\\.")[0];
		System.out.println("Base from uri " + bucketName);

		boolean isAdded = objectService.put(data);

		if (!isAdded) {
			String errorMessage = "Request should contain non-empty data";
			return Response.status(Response.Status.BAD_REQUEST).entity(errorMessage).build();
		}

		return Response.ok().header("ETag", "testatsattas").build();
	}

	/**
	 * Returns some or all (up to 1,000) of the objects in a bucket with each request
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html">List Objects</a>
	 */
	@Path("/list") @GET
	@Produces(MediaType.APPLICATION_XML)
	public Response list(@Context UriInfo uri) {
		return Response.ok().entity(objectService.getAll()).build();
	}

	/**
	 * Creates a copy of an object that is already stored in bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html">Copy Object</a>
	 */
	@Path("/{bucket}/{key}") @PUT
	@Produces(MediaType.APPLICATION_XML)
	public Response copy(@Context HttpHeaders httpHeaders, @PathParam("bucket") String bucket,
		@PathParam("key") String key) {
		System.out.println("Bucket: " + bucket);
		System.out.println("Key: " + key);

		String copySource = httpHeaders.getHeaderString("x-amz-copy-source");
		System.out.println("HEader: " + copySource);

		if (copySource == null) {
			String errorMessage = "Request header should contain 'x-amz-copy-source'";
			return Response.status(Response.Status.BAD_REQUEST).entity(errorMessage).build();
		}

		return Response.ok().entity(objectService.copy(bucket, key, copySource)).build();
	}

	/**
	 * Retrieves objects from Amazon S3
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html">Get Object</a>
	 */
	@Path("/{key}") @GET
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response get(@Context UriInfo uri, @PathParam("key") String key) throws Exception {
		System.out.println("Key: " + key);

		byte[] byteArray = objectService.download(key);

		return Response.ok(byteArray, "application/octet-stream").build();
	}

	/**
	 * Removes the null version (if there is one) of an object and inserts a delete marker, which becomes the latest version
	 * of the object
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html">Delete Object</a>
	 */
	@Path("/{key}") @DELETE
	public Response delete(@Context UriInfo uri, @PathParam("key") String key) {
		System.out.println("Key: " + key);

		String url = uri.getRequestUri().toASCIIString();
		System.out.println("URI " + url);

		String bucketName = url.split("\\.")[0];
		System.out.println("Base from uri " + bucketName);

		boolean isDeleted = objectService.delete(key);

		System.out.println("Has been deleted " + isDeleted);

		if (!isDeleted) {
			return Response.status(Response.Status.NOT_FOUND).build();
		}

		return Response.noContent().build();
	}
}
