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
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.s3.entity.DefaultRetention;
import org.unigrid.hedgehog.s3.entity.LegalHold;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.entity.ObjectLockConfiguration;
import org.unigrid.hedgehog.s3.entity.ObjectLockRule;
import org.unigrid.hedgehog.s3.entity.TimestampAdapter;
import org.unigrid.hedgehog.s3.error.S3ErrorCode;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Retention;
import org.unigrid.hedgehog.s3.index.RetentionMode;
import org.unigrid.hedgehog.s3.index.Versioning;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Object Lock: the default retention of a bucket, and the retention and legal hold of each version, which keep the
   version from being removed. The checks that guard removing a version or changing its lock run inside the index
   write they guard, so no request can change the version in between. */
@RequiredArgsConstructor
final class ObjectLocks {
	static final String ENABLED = "Enabled";

	private static final String MODE = "x-amz-object-lock-mode";
	private static final String UNTIL = "x-amz-object-lock-retain-until-date";
	private static final String LEGAL_HOLD = "x-amz-object-lock-legal-hold";
	private static final String BYPASS = "x-amz-bypass-governance-retention";
	private static final String ON = "ON";
	private static final String OFF = "OFF";
	private static final int MAX_DAYS = 36_500;
	private static final int MAX_YEARS = 100;
	private static final TimestampAdapter TIMESTAMPS = new TimestampAdapter();

	private final Namespace namespace;
	private final Clock clock;

	static ObjectLockConfiguration configuration(final Bucket bucket) {
		if (!bucket.objectLock()) {
			throw new S3Exception(S3ErrorCode.ObjectLockConfigurationNotFoundError);
		}

		return Optional.ofNullable(bucket.configurations().get(Bucket.OBJECT_LOCK))
			.map(document -> Requests.kept(ObjectLockConfiguration.class, document))
			.orElseGet(() -> new ObjectLockConfiguration(ENABLED, null));
	}

	/* Also turns Object Lock on for a bucket with versioning enabled, as S3 lets it, but never off */
	Response configure(final String name, final ContainerRequestContext request) throws IOException,
		BlobStoreException, NamespaceException, MalformedXmlException {

		Requests.bucket(namespace, name);

		final String document = Requests.digestedXml(request);

		validate(Requests.parse(ObjectLockConfiguration.class, document));
		namespace.updateBucket(name, bucket -> {
			if (!bucket.objectLock() && bucket.versioning() != Versioning.ENABLED) {
				throw new S3Exception(S3ErrorCode.InvalidBucketState, "Versioning must be 'Enabled' on the "
					+ "bucket to apply a Object Lock configuration");
			}

			return bucket.configured(Bucket.OBJECT_LOCK, document).toBuilder().objectLock(true).build();
		});

		return Response.ok().build();
	}

	/* Gives a version the retention and legal hold its request asks for, or the default retention of its bucket */
	static ObjectVersion.ObjectVersionBuilder lock(final ObjectVersion.ObjectVersionBuilder version,
		final Bucket bucket, final HttpHeaders headers, final Instant now) {

		return lock(version, bucket, headers::getHeaderString, now);
	}

	/* From headers, or from the fields of a browser form upload, which has them in their place */
	static ObjectVersion.ObjectVersionBuilder lock(final ObjectVersion.ObjectVersionBuilder version,
		final Bucket bucket, final Function<String, String> header, final Instant now) {

		final Optional<Retention> retention = requested(header, now);
		final Optional<Boolean> legalHold = Optional.ofNullable(header.apply(LEGAL_HOLD))
			.map(status -> status(status, new S3Exception(S3ErrorCode.InvalidArgument,
			"Legal Hold must be either of 'ON' or 'OFF'")));

		if (!bucket.objectLock() && (retention.isPresent() || legalHold.isPresent())) {
			throw unlockable();
		}

		return version.retention(retention.orElseGet(() -> defaultRetention(bucket, now)))
			.legalHold(legalHold.orElse(false));
	}

	/* A write that locks the version it stores has to carry a digest of its body, as S3 has it */
	static void checkDigest(final ContainerRequestContext request) {
		final boolean locking = Stream.of(MODE, UNTIL, LEGAL_HOLD).anyMatch(name -> Objects.nonNull(request
			.getHeaderString(name)));

		if (locking && !Requests.digested(request)) {
			throw new S3Exception(S3ErrorCode.InvalidRequest, "Content-MD5 OR x-amz-checksum- HTTP header is "
				+ "required for Put Object requests with Object Lock parameters");
		}
	}

	static Response.ResponseBuilder describe(final Response.ResponseBuilder response, final Bucket bucket,
		final ObjectVersion version) {

		Optional.ofNullable(version.retention()).ifPresent(retention -> response.header(MODE, retention.mode())
			.header(UNTIL, TIMESTAMPS.marshal(retention.until())));

		return bucket.objectLock() ? response.header(LEGAL_HOLD, version.legalHold() ? ON : OFF) : response;
	}

	static boolean bypassing(final ContainerRequestContext request) {
		return Boolean.parseBoolean(request.getHeaderString(BYPASS));
	}

	/* The guard of removing a version for good, which runs in the index write that removes it */
	static boolean removable(final ObjectVersion version, final Instant at, final boolean bypass) {
		if (version.locked(at, bypass)) {
			throw denied();
		}

		return true;
	}

	Response retention(final String bucket, final String key, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException {

		requireLock(Requests.bucket(namespace, bucket));

		final Retention retention = Optional.ofNullable(Versions.readable(namespace, bucket, key,
			query.getFirst(Versions.VERSION_ID)).retention())
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchObjectLockConfiguration));

		return Responses.xml(new org.unigrid.hedgehog.s3.entity.Retention(retention.mode().name(),
			retention.until()));
	}

	Response legalHold(final String bucket, final String key, final MultivaluedMap<String, String> query)
		throws IOException, BlobStoreException {

		requireLock(Requests.bucket(namespace, bucket));

		final boolean held = Versions.readable(namespace, bucket, key, query.getFirst(Versions.VERSION_ID))
			.legalHold();

		return Responses.xml(new LegalHold(held ? ON : OFF));
	}

	/* A retention that holds only grows, in its own mode, unless a request bypasses governance */
	Response retain(final String bucket, final String key, final MultivaluedMap<String, String> query,
		final ContainerRequestContext request) throws IOException, BlobStoreException, MalformedXmlException {

		requireLock(Requests.bucket(namespace, bucket));

		final Instant now = Requests.now(clock);
		final Retention next = retention(Requests.parse(org.unigrid.hedgehog.s3.entity.Retention.class,
			Requests.digestedXml(request)), now);
		final boolean bypass = bypassing(request);

		return change(bucket, key, query, current -> {
			if (Objects.nonNull(current.retention()) && !current.retention().yieldsTo(next, now, bypass)) {
				throw denied();
			}

			return current.toBuilder().retention(next).build();
		});
	}

	Response hold(final String bucket, final String key, final MultivaluedMap<String, String> query,
		final ContainerRequestContext request) throws IOException, BlobStoreException, MalformedXmlException {

		requireLock(Requests.bucket(namespace, bucket));

		final boolean held = status(Requests.parse(LegalHold.class, Requests.digestedXml(request)).getStatus(),
			new S3Exception(S3ErrorCode.MalformedXML));

		return change(bucket, key, query, current -> current.toBuilder().legalHold(held).build());
	}

	private Response change(final String bucket, final String key, final MultivaluedMap<String, String> query,
		final UnaryOperator<ObjectVersion> change) throws IOException, BlobStoreException {

		final ObjectVersion version = Versions.readable(namespace, bucket, key, query.getFirst(Versions.VERSION_ID));

		namespace.update(bucket, key, version.versionId(), change)
			.orElseThrow(() -> new S3Exception(S3ErrorCode.NoSuchVersion));

		return Response.ok().build();
	}

	private static void validate(final ObjectLockConfiguration configuration) {
		final Optional<ObjectLockRule> rule = Optional.ofNullable(configuration.getRule());

		if (!ENABLED.equals(configuration.getObjectLockEnabled()) || rule.isPresent()
			&& Objects.isNull(rule.get().getDefaultRetention())) {

			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		rule.map(ObjectLockRule::getDefaultRetention).ifPresent(ObjectLocks::validate);
	}

	/* A default retention runs for either days or years */
	private static void validate(final DefaultRetention retention) {
		mode(retention.getMode(), new S3Exception(S3ErrorCode.MalformedXML));

		if (Objects.isNull(retention.getDays()) == Objects.isNull(retention.getYears())) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		final int period = Objects.requireNonNullElseGet(retention.getDays(), retention::getYears);

		if (period <= 0 || period > (Objects.isNull(retention.getDays()) ? MAX_YEARS : MAX_DAYS)) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "Default retention period must be a positive "
				+ "integer value of at most " + MAX_DAYS + " days or " + MAX_YEARS + " years.");
		}
	}

	private static Retention defaultRetention(final Bucket bucket, final Instant now) {
		final Optional<DefaultRetention> retention = Optional.ofNullable(bucket.configurations()
			.get(Bucket.OBJECT_LOCK)).map(document -> Requests.kept(ObjectLockConfiguration.class, document)
			.getRule()).map(ObjectLockRule::getDefaultRetention);

		return retention.map(found -> new Retention(RetentionMode.valueOf(found.getMode()), until(found, now)))
			.orElse(null);
	}

	private static Instant until(final DefaultRetention retention, final Instant now) {
		return Objects.isNull(retention.getDays()) ? now.atZone(ZoneOffset.UTC).plusYears(retention.getYears())
			.toInstant() : now.plus(retention.getDays(), ChronoUnit.DAYS);
	}

	private static Optional<Retention> requested(final Function<String, String> header, final Instant now) {
		final String mode = header.apply(MODE);
		final String until = header.apply(UNTIL);

		if (Objects.isNull(mode) != Objects.isNull(until)) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, UNTIL + " and " + MODE
				+ " must both be supplied");
		}

		return Objects.isNull(mode) ? Optional.empty() : Optional.of(future(new Retention(mode(mode,
			new S3Exception(S3ErrorCode.InvalidArgument, "Unknown wormMode directive.")), date(until)), now));
	}

	/* What a retention document asks for, where one without mode and date lifts the retention there is */
	private static Retention retention(final org.unigrid.hedgehog.s3.entity.Retention document, final Instant now) {
		if (Objects.isNull(document.getMode()) && Objects.isNull(document.getRetainUntilDate())) {
			return null;
		}

		if (Objects.isNull(document.getRetainUntilDate())) {
			throw new S3Exception(S3ErrorCode.MalformedXML);
		}

		return future(new Retention(mode(document.getMode(), new S3Exception(S3ErrorCode.MalformedXML)),
			document.getRetainUntilDate()), now);
	}

	private static RetentionMode mode(final String mode, final S3Exception refusal) {
		return Arrays.stream(RetentionMode.values()).filter(value -> value.name().equals(mode)).findFirst()
			.orElseThrow(() -> refusal);
	}

	private static Instant date(final String date) {
		try {
			return Instant.parse(date);
		} catch (DateTimeParseException ex) {
			throw new S3Exception(S3ErrorCode.InvalidArgument,
				"The retain until date must be provided in ISO 8601 format");
		}
	}

	private static Retention future(final Retention retention, final Instant now) {
		if (!retention.until().isAfter(now)) {
			throw new S3Exception(S3ErrorCode.InvalidArgument, "The retain until date must be in the future!");
		}

		return retention;
	}

	private static boolean status(final String status, final S3Exception refusal) {
		if (!ON.equals(status) && !OFF.equals(status)) {
			throw refusal;
		}

		return ON.equals(status);
	}

	private static void requireLock(final Bucket bucket) {
		if (!bucket.objectLock()) {
			throw unlockable();
		}
	}

	private static S3Exception unlockable() {
		return new S3Exception(S3ErrorCode.InvalidRequest, "Bucket is missing Object Lock Configuration");
	}

	private static S3Exception denied() {
		return new S3Exception(S3ErrorCode.AccessDenied, "Access Denied because object protected by object lock.");
	}
}
