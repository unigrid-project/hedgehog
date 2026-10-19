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

package org.unigrid.hedgehog.s3.lifecycle;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.s3.entity.MalformedXmlException;
import org.unigrid.hedgehog.s3.error.S3Exception;
import org.unigrid.hedgehog.s3.index.Bucket;
import org.unigrid.hedgehog.s3.index.ListQuery;
import org.unigrid.hedgehog.s3.index.Listing;
import org.unigrid.hedgehog.s3.index.Namespace;
import org.unigrid.hedgehog.s3.index.NamespaceException;
import org.unigrid.hedgehog.s3.index.ObjectVersion;
import org.unigrid.hedgehog.s3.index.Upload;
import org.unigrid.hedgehog.s3.index.VersionEntry;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Applies the lifecycle rules of every bucket in rounds: it expires current versions, removes expired delete
   markers and noncurrent versions, and aborts incomplete multipart uploads. A round lists each bucket a page at a
   time and gathers the versions of one key before it changes any of them, so no page resumes after a version the
   round itself removed. Every change is guarded by what the listing showed, so a request that changed the key
   first wins, and Object Lock outranks every rule. Unless its interval is zero, a background thread runs a round
   every interval. */
@Slf4j
public final class LifecycleSweeper implements AutoCloseable {
	private static final int PAGE = 1000;

	@FunctionalInterface
	private interface Change {
		void run() throws IOException, BlobStoreException, NamespaceException;
	}

	private final Namespace namespace;
	private final Clock clock;
	@Getter private final Duration interval;
	private final int page;
	private final Optional<ScheduledExecutorService> rounds;
	private volatile boolean closed;

	LifecycleSweeper(final Namespace namespace, final Clock clock, final Duration interval, final int page) {
		if (interval.isNegative()) {
			throw new IllegalArgumentException("The interval between lifecycle rounds must not be negative");
		}

		this.namespace = namespace;
		this.clock = clock;
		this.interval = interval;
		this.page = page;

		rounds = interval.isZero() ? Optional.empty() : Optional.of(Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().daemon().name("lifecycle-sweeper").factory()));
	}

	public static LifecycleSweeper start(final Namespace namespace, final Clock clock, final Duration interval) {
		final LifecycleSweeper sweeper = new LifecycleSweeper(namespace, clock, interval, PAGE);
		final long millis = interval.toMillis();

		sweeper.rounds.ifPresent(executor -> executor.scheduleWithFixedDelay(sweeper::sweepQuietly, millis, millis,
			TimeUnit.MILLISECONDS));

		return sweeper;
	}

	/* Runs one round over every bucket with lifecycle rules */
	public void sweep() throws IOException, BlobStoreException {
		final Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);

		for (Bucket bucket : namespace.buckets()) {
			final List<Rule> rules = rules(bucket);

			if (!rules.isEmpty() && !closed) {
				sweepVersions(bucket.name(), rules, now);
				sweepUploads(bucket.name(), rules, now);
			}
		}
	}

	/* Lets a round under way stop between pages and waits for it */
	@Override
	public void close() {
		closed = true;
		rounds.ifPresent(ExecutorService::close);
	}

	/* A failed round leaves the rest to the next one */
	private void sweepQuietly() {
		try {
			sweep();
		} catch (IOException | BlobStoreException | RuntimeException ex) {
			log.warn("Applying the lifecycle rules of the S3 buckets failed and waits for the next round", ex);
		}
	}

	private static List<Rule> rules(final Bucket bucket) {
		try {
			return Lifecycle.enabled(bucket);
		} catch (IOException | MalformedXmlException | S3Exception ex) {
			log.warn("The lifecycle configuration of S3 bucket {} does not read and is left alone",
				bucket.name(), ex);
			return List.of();
		}
	}

	/* The versions of a key are only complete once the listing has moved past the key or ended */
	private void sweepVersions(final String bucket, final List<Rule> rules, final Instant now) throws IOException,
		BlobStoreException {

		final List<ObjectVersion> versions = new ArrayList<>();
		ListQuery query = ListQuery.builder().limit(page).build();
		Listing<VersionEntry> listing;

		do {
			listing = namespace.listVersions(bucket, query);
			gather(bucket, versions, listing.entries(), rules, now);
			query = resumed(query, listing);
		} while (listing.truncated() && !closed);

		if (!versions.isEmpty() && !listing.truncated()) {
			expire(bucket, versions, rules, now);
		}
	}

	/* Adds the entries to the versions of the key they belong to, and expires each key the entries move past */
	private void gather(final String bucket, final List<ObjectVersion> versions, final List<VersionEntry> entries,
		final List<Rule> rules, final Instant now) throws IOException, BlobStoreException {

		for (VersionEntry entry : entries) {
			if (!versions.isEmpty() && !versions.getFirst().key().equals(entry.version().key())) {
				expire(bucket, versions, rules, now);
				versions.clear();
			}

			versions.add(entry.version());
		}
	}

	/* The versions of one key, from the newest */
	private void expire(final String bucket, final List<ObjectVersion> versions, final List<Rule> rules,
		final Instant now) throws IOException, BlobStoreException {

		final ObjectVersion current = versions.getFirst();

		if (current.deleteMarker() && versions.size() == 1
			&& any(rules, rule -> rule.removesMarker(current, now))) {

			remove(bucket, current, now);
		} else if (!current.deleteMarker() && any(rules, rule -> rule.expires(current, now))) {
			apply(() -> namespace.delete(bucket, current.key(), now,
				latest -> latest.equals(Optional.of(current))));
		}

		expireNoncurrent(bucket, versions, rules, now);
	}

	/* A version is noncurrent since the newer version after it was written */
	private void expireNoncurrent(final String bucket, final List<ObjectVersion> versions, final List<Rule> rules,
		final Instant now) throws IOException, BlobStoreException {

		for (int i = 1; i < versions.size(); i++) {
			final ObjectVersion version = versions.get(i);
			final Instant since = versions.get(i - 1).lastModified();
			final int newer = i - 1;

			if (any(rules, rule -> rule.expiresNoncurrent(version, since, newer, now))) {
				remove(bucket, version, now);
			}
		}
	}

	private void remove(final String bucket, final ObjectVersion version, final Instant now) throws IOException,
		BlobStoreException {

		apply(() -> namespace.remove(bucket, version.key(), version.versionId(),
			found -> found.equals(version) && !found.locked(now, false)));
	}

	/* An aborted upload is gone, so a page resumes after the last upload it kept, or where it started */
	private void sweepUploads(final String bucket, final List<Rule> rules, final Instant now) throws IOException,
		BlobStoreException {

		if (rules.stream().noneMatch(rule -> rule.abortDays() > 0)) {
			return;
		}

		ListQuery query = ListQuery.builder().limit(page).build();
		Listing<Upload> listing;

		do {
			listing = namespace.listUploads(bucket, query);

			for (Upload upload : listing.entries()) {
				if (any(rules, rule -> rule.aborts(upload, now))) {
					apply(() -> namespace.abortUpload(bucket, upload.key(), upload.uploadId()));
				} else {
					query = query.toBuilder().keyMarker(upload.key()).idMarker(upload.uploadId())
						.build();
				}
			}
		} while (listing.truncated() && !closed);
	}

	private static ListQuery resumed(final ListQuery query, final Listing<?> listing) {
		return listing.next().map(next -> query.toBuilder().keyMarker(next.key()).idMarker(next.id()).build())
			.orElse(query);
	}

	private static boolean any(final List<Rule> rules, final Predicate<Rule> applies) {
		return rules.stream().anyMatch(applies);
	}

	/* A change the index refuses because a request changed the key or bucket first is left to that request */
	private static void apply(final Change change) throws IOException, BlobStoreException {
		try {
			change.run();
		} catch (NamespaceException ex) {
			log.debug("A lifecycle change gave way to a request that came first", ex);
		}
	}
}
