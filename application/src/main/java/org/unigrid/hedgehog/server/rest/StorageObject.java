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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.ArrayList;
import java.util.Arrays;
import org.unigrid.hedgehog.model.s3.entity.CopyObjectResult;
import org.unigrid.hedgehog.model.s3.entity.ListBucketResult;

@Path("/storage-object")
@Consumes(MediaType.MULTIPART_FORM_DATA)
public class StorageObject {
	
	/**
	 * Adds an object to a bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html">Put Object</a>
	 */
	@Path("/{key}") @PUT
	public Response create(@Context UriInfo uri, @PathParam("key") String key) {
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
		return Response.ok().entity(new ListBucketResult()).build();
	}

	/**
	 * Creates a copy of an object that is already stored in bucket
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CopyObject.html">Copy Object</a>
	 */
	@Path("/{bucket}/{key}") @PUT
	public Response copy(@Context HttpHeaders httpHeaders, @PathParam("bucket") String bucket, @PathParam("key") String key) {
		// request header should contain 'x-amz-copy-source'
		return Response.ok().entity(new CopyObjectResult()).build();
	}

	/**
	 * Retrieves objects from Amazon S3
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html">Get Object</a>
	 */
	@Path("/{key}") @GET
	@Produces(MediaType.MULTIPART_FORM_DATA) //should be bytes of object data
	public Response get(@Context UriInfo uri, @PathParam("key") String key) {
		return Response.ok().entity(new ArrayList<>(Arrays.asList("A", "B", "C"))).build();
	}

	/**
	 * Removes the null version (if there is one) of an object and inserts a delete marker, which becomes the latest version
	 * of the object
	 *
	 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteObject.html">Delete Object</a>
	 */
	@Path("/{key}") @DELETE
	public Response delete(@Context UriInfo uri, @PathParam("key") String key) {
		return Response.status(Response.Status.NO_CONTENT).build();
	}
}
