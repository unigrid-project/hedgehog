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

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.entity.VersioningConfiguration;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Versioning;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

final class Versions {
	static final String VERSION_ID = "versionId";

	private static final String ENABLED = "Enabled";
	private static final String SUSPENDED = "Suspended";
	private static final Map<String, Versioning> STATES = Map.of(ENABLED, Versioning.ENABLED, SUSPENDED,
		Versioning.SUSPENDED);

	/* What a delete did: the version it was asked to remove, or the delete marker it left in place of the key */
	record Deleted(String versionId, boolean deleteMarker) {
	}

	private Versions() {
		/* Static helpers only */
	}

	/* A bucket that never had versioning has no status to answer with */
	static VersioningConfiguration configuration(final Bucket bucket) {
		final String status = switch (bucket.versioning()) {
			case ENABLED -> ENABLED;
			case SUSPENDED -> SUSPENDED;
			case UNVERSIONED -> null;
		};

		return new VersioningConfiguration(status, null);
	}

	/* Versioning can be enabled and suspended, but a bucket never goes back to having had none */
	static Response configure(final Namespace namespace, final String name, final ContainerRequestContext request)
		throws IOException, BlobStoreException, NamespaceException, MalformedXmlException {

		Requests.bucket(namespace, name);

		final VersioningConfiguration configuration = Requests.xml(VersioningConfiguration.class, request)
			.orElseThrow(() -> new S3Exception(S3ErrorCode.MalformedXML));
		final Versioning state = Optional.ofNullable(configuration.getStatus()).map(STATES::get)
			.orElseThrow(() -> new S3Exception(S3ErrorCode.IllegalVersioningConfigurationException));

		if (ENABLED.equals(configuration.getMfaDelete())) {
			throw new S3Exception(S3ErrorCode.NotImplemented, "MFA delete is not implemented.");
		}

		namespace.updateBucket(name, bucket -> {
			if (bucket.objectLock() && state != Versioning.ENABLED) {
				throw new S3Exception(S3ErrorCode.InvalidBucketState, "An Object Lock configuration is "
					+ "present on this bucket, so the versioning state cannot be changed.");
			}

			return bucket.toBuilder().versioning(state).build();
		});

		return Response.ok().build();
	}

	/* Deleting what is not there succeeds, as it does in S3, while a version Object Lock keeps is refused */
	static Deleted delete(final Namespace namespace, final Instant at, final String bucket, final String key,
		final String versionId, final boolean bypass) throws IOException, BlobStoreException, NamespaceException {

		Requests.key(key);

		if (Objects.isNull(versionId)) {
			final Optional<ObjectVersion> marker = namespace.delete(bucket, key, at, latest -> true);
			return new Deleted(marker.map(ObjectVersion::versionId).orElse(null), marker.isPresent());
		}

		final Optional<ObjectVersion> removed = namespace.remove(bucket, key, versionId,
			version -> ObjectLocks.removable(version, at, bypass));

		if (removed.isEmpty()) {
			Requests.bucket(namespace, bucket);
		}

		return new Deleted(versionId, removed.map(ObjectVersion::deleteMarker).orElse(false));
	}

	/* The version named, or the latest one without a version id, which may be a delete marker */
	static ObjectVersion find(final Namespace namespace, final String bucket, final String key,
		final String versionId) throws IOException, BlobStoreException {

		Requests.key(key);

		final Optional<ObjectVersion> found = Objects.isNull(versionId) ? namespace.latest(bucket, key)
			: namespace.version(bucket, key, versionId);

		if (found.isEmpty()) {
			Requests.bucket(namespace, bucket);
			throw new S3Exception(Objects.isNull(versionId) ? S3ErrorCode.NoSuchKey : S3ErrorCode.NoSuchVersion);
		}

		return found.get();
	}

	/* As S3 answers it, a delete marker hides its key, and is no object to read when named by its version id */
	static ObjectVersion readable(final Namespace namespace, final String bucket, final String key,
		final String versionId) throws IOException, BlobStoreException {

		final ObjectVersion version = find(namespace, bucket, key, versionId);

		if (version.deleteMarker()) {
			final S3Exception refusal = Objects.isNull(versionId) ? new S3Exception(S3ErrorCode.NoSuchKey)
				: new S3Exception(S3ErrorCode.MethodNotAllowed)
				.header(HttpHeaders.LAST_MODIFIED, Date.from(version.lastModified()));

			throw refusal.header(Responses.DELETE_MARKER, true)
				.header(Responses.VERSION_ID, version.versionId());
		}

		return version;
	}
}
