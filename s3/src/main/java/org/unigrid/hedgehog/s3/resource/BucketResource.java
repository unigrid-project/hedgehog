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

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.unigrid.hedgehog.s3.entity.CreateBucketConfiguration;
import org.unigrid.hedgehog.s3.entity.Delete;
import org.unigrid.hedgehog.s3.entity.DeleteError;
import org.unigrid.hedgehog.s3.entity.DeleteObject;
import org.unigrid.hedgehog.s3.entity.DeleteResult;
import org.unigrid.hedgehog.s3.entity.DeletedObject;
import org.unigrid.hedgehog.s3.entity.LocationConstraint;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.http.Subresource;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.Versioning;
import org.unigrid.hedgehog.s3.request.BucketNames;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

@Path("/{bucket}")
public class BucketResource {
	private static final String OBJECT_LOCK = "x-amz-bucket-object-lock-enabled";
	private static final String REGION = "x-amz-bucket-region";
	private static final String DEFAULT_REGION = "us-east-1";
	private static final int MAX_DELETE = 1000;

	private final Namespace namespace;
	private final Clock clock;
	private final Listings listings;
	private final ObjectLocks locks;

	public BucketResource(final Namespace namespace, final Clock clock) {
		this.namespace = namespace;
		this.clock = clock;
		listings = new Listings(namespace);
		locks = new ObjectLocks(namespace, clock);
	}

	@PUT
	public Response create(@PathParam("bucket") final String name, @Context final UriInfo uri,
		@Context final HttpHeaders headers, @Context final ContainerRequestContext request) throws IOException,
		BlobStoreException, NamespaceException, MalformedXmlException {

		final Subresource subresource = Requests.subresource(uri, Subresource.NONE, Subresource.TAGGING,
			Subresource.VERSIONING, Subresource.OBJECT_LOCK, Subresource.LIFECYCLE);

		return subresource == Subresource.NONE ? createBucket(name, headers, request) : configure(name, subresource,
			request);
	}

	/* A bucket gets Object Lock when it is created, along with versioning, which it then keeps enabled */
	private Response createBucket(final String name, final HttpHeaders headers,
		final ContainerRequestContext request) throws IOException, BlobStoreException, MalformedXmlException {

		if (!BucketNames.valid(name)) {
			throw new S3Exception(S3ErrorCode.InvalidBucketName);
		}

		final boolean locked = Boolean.parseBoolean(headers.getHeaderString(OBJECT_LOCK));
		final Bucket bucket = Bucket.builder().name(name).created(clock.instant()).location(Requests
			.xml(CreateBucketConfiguration.class, request).map(CreateBucketConfiguration::getLocationConstraint)
			.orElse(null)).objectLock(locked).versioning(locked ? Versioning.ENABLED : null).build();

		if (!namespace.createBucket(bucket)) {
			throw new S3Exception(S3ErrorCode.BucketAlreadyOwnedByYou);
		}

		return Response.ok().header(HttpHeaders.LOCATION, "/" + name).build();
	}

	private Response configure(final String name, final Subresource subresource,
		final ContainerRequestContext request) throws IOException, BlobStoreException, NamespaceException,
		MalformedXmlException {

		return switch (subresource) {
			case TAGGING -> Tags.writeBucket(namespace, name, request);
			case VERSIONING -> Versions.configure(namespace, name, request);
			case OBJECT_LOCK -> locks.configure(name, request);
			case LIFECYCLE -> Lifecycles.configure(namespace, name, request);
			default -> throw new IllegalArgumentException("No bucket configuration is named " + subresource);
		};
	}

	@HEAD
	public Response head(@PathParam("bucket") final String name) throws IOException, BlobStoreException {
		final Bucket bucket = Requests.bucket(namespace, name);
		return Response.ok().header(REGION, Objects.requireNonNullElse(bucket.location(), DEFAULT_REGION)).build();
	}

	@GET
	public Response read(@PathParam("bucket") final String name, @Context final UriInfo uri) throws IOException,
		BlobStoreException {

		final Subresource subresource = Requests.subresource(uri, Subresource.NONE, Subresource.LIST_TYPE,
			Subresource.LOCATION, Subresource.TAGGING, Subresource.UPLOADS, Subresource.VERSIONING,
			Subresource.VERSIONS, Subresource.OBJECT_LOCK, Subresource.LIFECYCLE);
		final Bucket bucket = Requests.bucket(namespace, name);

		return Responses.xml(switch (subresource) {
			case NONE -> listings.objects(name, uri.getQueryParameters());
			case LIST_TYPE -> listings.objectsV2(name, uri.getQueryParameters());
			case UPLOADS -> listings.uploads(name, uri.getQueryParameters());
			case VERSIONS -> listings.versions(name, uri.getQueryParameters());
			default -> configuration(bucket, subresource);
		});
	}

	/* The documents of the subresources that describe a bucket rather than list what it holds */
	private static Object configuration(final Bucket bucket, final Subresource subresource) {
		return switch (subresource) {
			case LOCATION -> new LocationConstraint(bucket.location());
			case TAGGING -> Tags.bucket(bucket);
			case VERSIONING -> Versions.configuration(bucket);
			case OBJECT_LOCK -> ObjectLocks.configuration(bucket);
			case LIFECYCLE -> Lifecycles.configuration(bucket);
			default -> throw new IllegalArgumentException("No bucket configuration is named " + subresource);
		};
	}

	@DELETE
	public Response delete(@PathParam("bucket") final String name, @Context final UriInfo uri) throws IOException,
		BlobStoreException, NamespaceException {

		final Subresource subresource = Requests.subresource(uri, Subresource.NONE, Subresource.TAGGING,
			Subresource.LIFECYCLE);

		if (subresource == Subresource.TAGGING) {
			return Tags.deleteBucket(namespace, name);
		} else if (subresource == Subresource.LIFECYCLE) {
			return Lifecycles.delete(namespace, name);
		}

		namespace.deleteBucket(name);
		return Response.noContent().build();
	}

	@POST
	public Response deleteObjects(@PathParam("bucket") final String name, @Context final UriInfo uri,
		@Context final ContainerRequestContext request) throws IOException, BlobStoreException, NamespaceException,
		MalformedXmlException {

		Requests.subresource(uri, Subresource.DELETE);
		Requests.bucket(namespace, name);

		final Delete delete = Requests.xml(Delete.class, request).filter(BucketResource::deletable)
			.orElseThrow(() -> new S3Exception(S3ErrorCode.MalformedXML));
		final DeleteResult result = new DeleteResult();

		for (DeleteObject object : delete.getObjects()) {
			try {
				result.getDeleted().add(deleteObject(name, object, ObjectLocks.bypassing(request)));
			} catch (S3Exception ex) {
				result.getErrors().add(new DeleteError(object.getKey(), object.getVersionId(),
					ex.getCode().name(), ex.getMessage()
				));
			}
		}

		if (Boolean.TRUE.equals(delete.getQuiet())) {
			result.getDeleted().clear();
		}

		return Responses.xml(result);
	}

	private DeletedObject deleteObject(final String bucket, final DeleteObject object, final boolean bypass)
		throws IOException, BlobStoreException, NamespaceException {

		final Versions.Deleted deleted = Versions.delete(namespace, Requests.now(clock), bucket, object.getKey(),
			object.getVersionId(), bypass);

		return deleted.deleteMarker() ? new DeletedObject(object.getKey(), object.getVersionId(), true,
			deleted.versionId()) : new DeletedObject(object.getKey(), object.getVersionId(), null, null);
	}

	private static boolean deletable(final Delete delete) {
		return !delete.getObjects().isEmpty() && delete.getObjects().size() <= MAX_DELETE
			&& delete.getObjects().stream().allMatch(object -> Objects.nonNull(object.getKey()));
	}
}
