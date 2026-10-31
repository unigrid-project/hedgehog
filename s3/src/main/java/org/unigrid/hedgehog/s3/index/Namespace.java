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

package org.unigrid.hedgehog.s3.index;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The buckets, object versions and multipart uploads of one S3 namespace, kept in a tree of immutable blobs that a
   root blob id names. Every mutation is durable in the local journal before it returns and atomic: guards,
   changes and assembly functions run under the writer lock against the current state, and when one throws or a
   guard fails nothing is written. Reads see a consistent snapshot and never wait for writers. One node writes a
   namespace at a time; several writers on one namespace, and anchoring its root anywhere but the local root file,
   are left for later. Unless its interval is zero, a background thread maintains the namespace, and right away
   once the memtable outgrows the flush size. */
@Slf4j
public final class Namespace implements AutoCloseable {
	private static final int SWEEP_BATCH = 1000;

	@FunctionalInterface
	private interface Mutation<T, E extends Exception> {
		T run(Transaction transaction, References references) throws IOException, BlobStoreException, E;
	}

	private final Tree tree;
	private final BlobStore blobs;
	private final NamespaceConfig config;
	private final Set<BlobId> deleting = ConcurrentHashMap.newKeySet();
	private final Optional<ScheduledExecutorService> maintenance;
	private final AtomicBoolean maintenanceRequested = new AtomicBoolean();

	private Namespace(final Tree tree, final BlobStore blobs, final NamespaceConfig config) {
		this.tree = tree;
		this.blobs = blobs;
		this.config = config;

		maintenance = config.getMaintenanceInterval().isZero() ? Optional.empty()
			: Optional.of(Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
			.name("namespace-maintenance").factory()));
	}

	public static Namespace open(final Path directory, final BlobStore blobs, final NamespaceConfig config)
		throws IOException, BlobStoreException {

		return new Namespace(Tree.open(directory, blobs, config, null), blobs, config).start();
	}

	/* Mounts the namespace a root names, such as on a fresh node recovering the namespace of a lost one */
	public static Namespace mount(final Path directory, final BlobStore blobs, final BlobId root,
		final NamespaceConfig config) throws IOException, BlobStoreException {

		return new Namespace(Tree.open(directory, blobs, config, Objects.requireNonNull(root)), blobs, config)
			.start();
	}

	/* The manifest the local root names, whose id grants read access to the whole namespace */
	public Optional<BlobId> root() {
		return tree.root();
	}

	public boolean createBucket(final Bucket bucket) throws IOException, BlobStoreException {
		return write((transaction, references) -> {
			final byte[] key = Keys.bucket(bucket.name());

			if (transaction.get(key).isPresent()) {
				return false;
			}

			transaction.put(key, Records.bucket(bucket));
			return true;
		});
	}

	public Optional<Bucket> bucket(final String name) throws IOException, BlobStoreException {
		return bucket(tree.snapshot(), name);
	}

	public List<Bucket> buckets() throws IOException, BlobStoreException {
		final List<Bucket> buckets = new ArrayList<>();

		for (Slot slot : slots(tree.snapshot(), Keys.buckets())) {
			buckets.add(Records.bucket(Keys.bucketName(slot.key()), slot.value()));
		}

		return buckets;
	}

	public Bucket updateBucket(final String name, final UnaryOperator<Bucket> change)
		throws IOException, BlobStoreException, NoSuchBucketException {

		return write((transaction, references) -> {
			final Bucket changed = change.apply(requireBucket(transaction, name));

			if (!changed.name().equals(name)) {
				throw new IllegalArgumentException("A bucket keeps its name");
			}

			transaction.put(Keys.bucket(name), Records.bucket(changed));
			return changed;
		});
	}

	public void deleteBucket(final String name) throws IOException, BlobStoreException, NamespaceException {
		write((transaction, references) -> {
			requireBucket(transaction, name);

			if (any(transaction, Keys.objects(Keys.VERSION, name, "")) || any(transaction,
				Keys.objects(Keys.UPLOAD, name, ""))) {

				throw new BucketNotEmptyException("Bucket " + name + " is not empty");
			}

			transaction.delete(Keys.bucket(name));
			return null;
		});
	}

	/* The newest version of the key, which may be a delete marker */
	public Optional<ObjectVersion> latest(final String bucket, final String key) throws IOException,
		BlobStoreException {

		return latest(tree.snapshot(), bucket, key);
	}

	public Optional<ObjectVersion> version(final String bucket, final String key, final String versionId)
		throws IOException, BlobStoreException {

		final Optional<Slot> slot = findVersion(tree.snapshot(), bucket, key, versionId);
		return slot.isPresent() ? Optional.of(version(slot.get())) : Optional.empty();
	}

	/* Stores a new version once the guard accepts the newest version there is. A bucket with versioning enabled
	   gives it a version id of its own, and any other bucket stores it as the null version in place of the one
	   before. The answer is the version as stored. */
	public ObjectVersion put(final ObjectVersion version, final Predicate<Optional<ObjectVersion>> guard)
		throws IOException, BlobStoreException, NamespaceException {

		return write((transaction, references) -> store(transaction, references,
			guarded(transaction, version.bucket(), version.key(), guard), version));
	}

	/* Stores a copy of a version once the guard accepts the newest version of the target key. The source is read
	   under the writer lock along with the write, so its blobs cannot be released and deleted before the copy
	   refers to them. The build gets the source as found then and answers the version to store, which is stored
	   the way put stores one. */
	public ObjectVersion copy(final VersionRef source, final Function<Optional<ObjectVersion>, ObjectVersion> build,
		final Predicate<Optional<ObjectVersion>> guard) throws IOException, BlobStoreException, NamespaceException {

		return write((transaction, references) -> {
			final ObjectVersion version = build.apply(find(transaction, source));

			return store(transaction, references, guarded(transaction, version.bucket(), version.key(), guard),
				version);
		});
	}

	/* Deletes the way a delete without a version id does once the guard accepts the newest version there is: an
	   unversioned bucket drops the null version, and any other bucket stores a delete marker, which the answer
	   holds */
	public Optional<ObjectVersion> delete(final String bucket, final String key, final Instant at,
		final Predicate<Optional<ObjectVersion>> guard) throws IOException, BlobStoreException, NamespaceException {

		return write((transaction, references) -> {
			final Bucket found = guarded(transaction, bucket, key, guard);

			if (found.versioning() == Versioning.UNVERSIONED) {
				dropNullVersion(transaction, references, bucket, key);
				return Optional.empty();
			}

			return Optional.of(store(transaction, references, found, ObjectVersion.builder().bucket(bucket)
				.key(key).deleteMarker(true).lastModified(at).build()));
		});
	}

	/* Removes one version for good once the guard accepts it, and answers the version removed */
	public Optional<ObjectVersion> remove(final String bucket, final String key, final String versionId,
		final Predicate<ObjectVersion> guard) throws IOException, BlobStoreException, ConditionFailedException {

		return write((transaction, references) -> {
			final Optional<Found> found = found(transaction, bucket, key, versionId);

			if (found.isEmpty()) {
				return Optional.empty();
			}

			final ObjectVersion version = found.get().version();

			check(guard.test(version));
			transaction.delete(found.get().slot().key());
			references.release(version.blobs());
			return Optional.of(version);
		});
	}

	/* Changes what a version carries besides its identity and content, such as its tags, retention or legal hold */
	public Optional<ObjectVersion> update(final String bucket, final String key, final String versionId,
		final UnaryOperator<ObjectVersion> change) throws IOException, BlobStoreException {

		return write((transaction, references) -> {
			final Optional<Found> found = found(transaction, bucket, key, versionId);

			if (found.isEmpty()) {
				return Optional.empty();
			}

			final ObjectVersion current = found.get().version();
			final ObjectVersion changed = change.apply(current);

			if (!identity(current).equals(identity(changed))) {
				throw new IllegalArgumentException("An update keeps the identity and content of a version");
			}

			transaction.put(found.get().slot().key(), Records.version(changed));
			return Optional.of(changed);
		});
	}

	/* The newest version of every key that is not a delete marker */
	public Listing<ObjectVersion> listObjects(final String bucket, final ListQuery query) throws IOException,
		BlobStoreException {

		return new Lister<ObjectVersion>(tree.snapshot(), Keys.VERSION, bucket, query, (parsed, value, first) -> {
			final ObjectVersion version = first ? version(parsed, value) : null;
			return Objects.isNull(version) || version.deleteMarker() ? null : version;
		}, version -> null).list(afterKey(Keys.VERSION, bucket, query), null);
	}

	public Listing<VersionEntry> listVersions(final String bucket, final ListQuery query) throws IOException,
		BlobStoreException {

		final Snapshot snapshot = tree.snapshot();
		final Optional<Slot> resume = query.idMarker().isEmpty() ? Optional.empty()
			: findVersion(snapshot, bucket, query.keyMarker(), query.idMarker());

		return new Lister<>(snapshot, Keys.VERSION, bucket, query,
			(parsed, value, first) -> new VersionEntry(version(parsed, value), first),
			entry -> entry.version().versionId()).list(resumeAfter(resume, Keys.VERSION, bucket, query),
			resume.isPresent() ? query.keyMarker() : null);
	}

	/* Starts an upload under a new upload id. The template only carries attributes, so it holds no parts. */
	public Upload createUpload(final Upload upload) throws IOException, BlobStoreException, NoSuchBucketException {
		if (!upload.template().parts().isEmpty()) {
			throw new IllegalArgumentException("An upload gets its content from its parts");
		}

		return write((transaction, references) -> {
			requireBucket(transaction, upload.bucket());

			final Upload stored = upload.toBuilder().uploadId(Keys.id(transaction.sequence())).build();

			transaction.put(Keys.upload(upload.bucket(), upload.key(), transaction.sequence()),
				Records.upload(stored));

			return stored;
		});
	}

	public Optional<Upload> upload(final String bucket, final String key, final String uploadId) throws IOException,
		BlobStoreException {

		final Optional<Slot> slot = findUpload(tree.snapshot(), bucket, key, uploadId);
		return slot.isPresent() ? Optional.of(upload(slot.get())) : Optional.empty();
	}

	/* Stores a part of the upload in place of any part with the same number */
	public Part putPart(final String bucket, final String key, final String uploadId, final Part part)
		throws IOException, BlobStoreException, NoSuchUploadException {

		return write((transaction, references) -> storePart(transaction, references,
			requireUpload(transaction, bucket, key, uploadId), part));
	}

	/* Stores a part copied from a version, which is read under the writer lock the way copy reads its source */
	public Part copyPart(final String bucket, final String key, final String uploadId, final VersionRef source,
		final Function<Optional<ObjectVersion>, Part> build) throws IOException, BlobStoreException,
		NamespaceException {

		return write((transaction, references) -> storePart(transaction, references,
			requireUpload(transaction, bucket, key, uploadId), build.apply(find(transaction, source))));
	}

	/* The parts of the upload numbered past the marker, at most limit of them */
	public Listing<Part> parts(final String bucket, final String key, final String uploadId, final int marker,
		final int limit) throws IOException, BlobStoreException, NoSuchUploadException {

		final Snapshot snapshot = tree.snapshot();
		final long upload = Keys.parse(requireUpload(snapshot, bucket, key, uploadId).key()).sequence();
		final Cursor cursor = snapshot.scan(Keys.after(Keys.part(upload, marker)), Keys.end(Keys.parts(upload)));
		final List<Part> parts = new ArrayList<>();

		for (Slot slot = cursor.next(); Objects.nonNull(slot) && limit > 0; slot = cursor.next()) {
			if (parts.size() == limit) {
				return new Listing<>(parts, List.of(), Optional.of(new Marker(key,
					String.valueOf(parts.getLast().number()))));
			}

			parts.add(Records.part(slot.value()));
		}

		return new Listing<>(parts, List.of(), Optional.empty());
	}

	/* Turns the upload into a version once the guard accepts the newest version there is. The assembly gets the
	   upload and all its parts in order and answers the version to store, which is stored the way put stores one. */
	public ObjectVersion completeUpload(final String bucket, final String key, final String uploadId,
		final BiFunction<Upload, List<Part>, ObjectVersion> assembly,
		final Predicate<Optional<ObjectVersion>> guard) throws IOException, BlobStoreException, NamespaceException {

		return write((transaction, references) -> {
			final Slot upload = requireUpload(transaction, bucket, key, uploadId);
			final Bucket found = requireBucket(transaction, bucket);

			check(guard.test(latest(transaction, bucket, key)));

			final List<Part> parts = dropUpload(transaction, references, upload);
			final ObjectVersion version = assembly.apply(upload(upload), parts);

			if (!version.bucket().equals(bucket) || !version.key().equals(key)) {
				throw new IllegalArgumentException("An upload completes as a version of its own key");
			}

			return store(transaction, references, found, version);
		});
	}

	public void abortUpload(final String bucket, final String key, final String uploadId) throws IOException,
		BlobStoreException, NoSuchUploadException {

		write((transaction, references) -> dropUpload(transaction, references,
			requireUpload(transaction, bucket, key, uploadId)));
	}

	public Listing<Upload> listUploads(final String bucket, final ListQuery query) throws IOException,
		BlobStoreException {

		final Snapshot snapshot = tree.snapshot();
		final Optional<Slot> resume = query.idMarker().isEmpty() ? Optional.empty()
			: findUpload(snapshot, bucket, query.keyMarker(), query.idMarker());

		return new Lister<>(snapshot, Keys.UPLOAD, bucket, query, (parsed, value, first) -> Records.upload(
			parsed.bucket(), parsed.key(), parsed.sequence(), value), Upload::uploadId).list(resumeAfter(resume,
			Keys.UPLOAD, bucket, query), resume.isPresent() ? query.keyMarker() : null);
	}

	/* Makes every mutation that returned before the call part of the namespace the root names */
	public void flush() throws IOException, BlobStoreException {
		tree.flush();
	}

	/* Merges every run into one, which drops what deleted records left behind */
	public void compact() throws IOException, BlobStoreException {
		tree.compact(true);
	}

	/* Flushes when the memtable is due, compacts, and deletes the superseded runs and manifests and the released
	   object blobs whose grace period is over. The background maintenance calls this every interval. */
	public void maintain() throws IOException, BlobStoreException {
		tree.maintain();
		sweepGarbage();
	}

	/* Waits for a maintenance round under way and stops the background. Writes not yet flushed stay in the journal
	   for the next open. */
	@Override
	public void close() throws IOException {
		maintenance.ifPresent(ExecutorService::close);
		tree.close();
	}

	private Namespace start() {
		final long interval = config.getMaintenanceInterval().toMillis();

		maintenance.ifPresent(executor -> executor.scheduleWithFixedDelay(this::maintainQuietly, interval, interval,
			TimeUnit.MILLISECONDS));

		return this;
	}

	/* A failed round leaves every write in the journal and the next round tries again */
	private void maintainQuietly() {
		maintenanceRequested.set(false);

		try {
			maintain();
		} catch (IOException | BlobStoreException | RuntimeException exception) {
			log.warn("Maintenance of the S3 namespace index failed and waits for its next round", exception);
		}
	}

	private <T, E extends Exception> T write(final Mutation<T, E> mutation) throws IOException, BlobStoreException,
		E {

		final T result = tree.write(transaction -> {
			final References references = new References();
			final T done = mutation.run(transaction, references);

			references.settle(transaction, config.getClock().instant().plus(config.getGrace()), deleting);
			return done;
		});

		if (tree.unflushedBytes() >= config.getFlushBytes() && !maintenanceRequested.getAndSet(true)) {
			maintenance.ifPresent(executor -> executor.execute(this::maintainQuietly));
		}

		return result;
	}

	/* Deletes the released blobs that are due. Each is claimed under the writer lock while nothing refers to it, so
	   a mutation trying to refer to it again fails instead of keeping a blob that is about to vanish, and it leaves
	   the queue only once the blob store confirms the delete, so a crash in between deletes it again later. */
	private void sweepGarbage() throws IOException, BlobStoreException {
		final Instant now = config.getClock().instant();
		final List<BlobId> claimed = claim(dueGarbage(now), now);

		try {
			for (BlobId blob : claimed) {
				Tree.delete(blobs, blob);
			}

			write((transaction, references) -> {
				claimed.forEach(blob -> transaction.delete(Keys.garbage(blob)));
				return null;
			});
		} finally {
			claimed.forEach(deleting::remove);
		}
	}

	/* Picks released blobs due at the instant from a snapshot, which a claim then has to confirm under the lock */
	List<BlobId> dueGarbage(final Instant now) throws IOException, BlobStoreException {
		final List<BlobId> due = new ArrayList<>();
		final Cursor cursor = tree.snapshot().scan(Keys.garbage(), Keys.end(Keys.garbage()));

		for (Slot slot = cursor.next(); Objects.nonNull(slot) && due.size() < SWEEP_BATCH; slot = cursor.next()) {
			if (isDue(slot.value(), now)) {
				due.add(Keys.garbageBlob(slot.key()));
			}
		}

		return due;
	}

	/* Claims the candidates that are still released and due at the instant. Between picking and claiming, a blob
	   may have been referred to and released again, which queued it anew with a later due time. */
	List<BlobId> claim(final List<BlobId> candidates, final Instant now) throws IOException, BlobStoreException {
		return write((transaction, references) -> {
			final List<BlobId> claimed = new ArrayList<>();

			for (BlobId blob : candidates) {
				final Optional<byte[]> queued = transaction.get(Keys.garbage(blob));
				final boolean due = queued.isPresent() && isDue(queued.get(), now);

				if (due && References.count(transaction, blob) == 0 && deleting.add(blob)) {
					claimed.add(blob);
				}
			}

			return claimed;
		});
	}

	/* Whether a queued release is due at the instant: the pick and the claim must judge it alike */
	private static boolean isDue(final byte[] queued, final Instant now) throws IOException {
		return !Codec.decode(queued, Codec::readInstant).isAfter(now);
	}

	private static void check(final boolean holds) throws ConditionFailedException {
		if (!holds) {
			throw new ConditionFailedException("The condition of the mutation does not hold");
		}
	}

	private static Optional<Bucket> bucket(final View view, final String name) throws IOException,
		BlobStoreException {

		final Optional<byte[]> value = view.get(Keys.bucket(name));
		return value.isPresent() ? Optional.of(Records.bucket(name, value.get())) : Optional.empty();
	}

	private static Bucket requireBucket(final View view, final String name) throws IOException, BlobStoreException,
		NoSuchBucketException {

		return bucket(view, name).orElseThrow(() -> new NoSuchBucketException("No bucket " + name));
	}

	/* The bucket of a write, once its guard accepts the newest version of the key as found under the writer lock */
	private static Bucket guarded(final View view, final String bucket, final String key,
		final Predicate<Optional<ObjectVersion>> guard) throws IOException, BlobStoreException, NamespaceException {

		final Bucket found = requireBucket(view, bucket);

		check(guard.test(latest(view, bucket, key)));
		return found;
	}

	/* A version found by its id, with the slot that holds it */
	private record Found(Slot slot, ObjectVersion version) {
	}

	private static Optional<Found> found(final View view, final String bucket, final String key,
		final String versionId) throws IOException, BlobStoreException {

		final Optional<Slot> slot = findVersion(view, bucket, key, versionId);
		return slot.isPresent() ? Optional.of(new Found(slot.get(), version(slot.get()))) : Optional.empty();
	}

	private static boolean any(final View view, final byte[] prefix) throws IOException, BlobStoreException {
		return Objects.nonNull(view.scan(prefix, Keys.end(prefix)).next());
	}

	private static List<Slot> slots(final View view, final byte[] prefix) throws IOException, BlobStoreException {
		final Cursor cursor = view.scan(prefix, Keys.end(prefix));
		final List<Slot> slots = new ArrayList<>();

		for (Slot slot = cursor.next(); Objects.nonNull(slot); slot = cursor.next()) {
			slots.add(slot);
		}

		return slots;
	}

	private static List<Object> identity(final ObjectVersion version) {
		return Arrays.asList(version.bucket(), version.key(), version.versionId(), version.parts());
	}

	private static ObjectVersion version(final Keys.Parsed parsed, final byte[] value) throws IOException {
		return Records.version(parsed.bucket(), parsed.key(), parsed.sequence(), value);
	}

	private static ObjectVersion version(final Slot slot) throws IOException {
		return version(Keys.parse(slot.key()), slot.value());
	}

	private static Upload upload(final Slot slot) throws IOException {
		final Keys.Parsed parsed = Keys.parse(slot.key());
		return Records.upload(parsed.bucket(), parsed.key(), parsed.sequence(), slot.value());
	}

	private static Optional<ObjectVersion> latest(final View view, final String bucket, final String key)
		throws IOException, BlobStoreException {

		final byte[] prefix = Keys.object(Keys.VERSION, bucket, key);
		final Slot slot = view.scan(prefix, Keys.end(prefix)).next();

		return Objects.isNull(slot) ? Optional.empty() : Optional.of(version(slot));
	}

	private static Optional<Slot> findVersion(final View view, final String bucket, final String key,
		final String versionId) throws IOException, BlobStoreException {

		if (ObjectVersion.NULL_VERSION.equals(versionId)) {
			return findNullVersion(view, bucket, key);
		}

		final OptionalLong sequence = Keys.sequence(versionId);

		if (sequence.isEmpty()) {
			return Optional.empty();
		}

		final byte[] slotKey = Keys.version(bucket, key, sequence.getAsLong());

		return view.get(slotKey).filter(value -> !Records.isNullVersion(value))
			.map(value -> new Slot(slotKey, value));
	}

	private static Optional<ObjectVersion> find(final View view, final VersionRef ref) throws IOException,
		BlobStoreException, NoSuchBucketException {

		requireBucket(view, ref.bucket());

		if (Objects.isNull(ref.versionId())) {
			return latest(view, ref.bucket(), ref.key());
		}

		final Optional<Slot> slot = findVersion(view, ref.bucket(), ref.key(), ref.versionId());
		return slot.isPresent() ? Optional.of(version(slot.get())) : Optional.empty();
	}

	private static Optional<Slot> findNullVersion(final View view, final String bucket, final String key)
		throws IOException, BlobStoreException {

		final byte[] prefix = Keys.object(Keys.VERSION, bucket, key);
		final Cursor cursor = view.scan(prefix, Keys.end(prefix));

		for (Slot slot = cursor.next(); Objects.nonNull(slot); slot = cursor.next()) {
			if (Records.isNullVersion(slot.value())) {
				return Optional.of(slot);
			}
		}

		return Optional.empty();
	}

	private static ObjectVersion store(final Transaction transaction, final References references,
		final Bucket bucket, final ObjectVersion version) throws IOException, BlobStoreException {

		final boolean versioned = bucket.versioning() == Versioning.ENABLED;

		if (!versioned) {
			dropNullVersion(transaction, references, bucket.name(), version.key());
		}

		final ObjectVersion stored = version.toBuilder().versionId(versioned ? Keys.id(transaction.sequence())
			: ObjectVersion.NULL_VERSION).build();

		transaction.put(Keys.version(bucket.name(), version.key(), transaction.sequence()), Records.version(stored));
		references.retain(stored.blobs());
		return stored;
	}

	private static void dropNullVersion(final Transaction transaction, final References references,
		final String bucket, final String key) throws IOException, BlobStoreException {

		final Optional<Slot> found = findNullVersion(transaction, bucket, key);

		if (found.isPresent()) {
			transaction.delete(found.get().key());
			references.release(version(found.get()).blobs());
		}
	}

	/* Stores the part in place of any part of the upload with the same number */
	private static Part storePart(final Transaction transaction, final References references, final Slot upload,
		final Part part) throws IOException, BlobStoreException {

		final byte[] slotKey = Keys.part(Keys.parse(upload.key()).sequence(), part.number());
		final Optional<byte[]> replaced = transaction.get(slotKey);

		if (replaced.isPresent()) {
			references.release(Records.part(replaced.get()).blobs());
		}

		transaction.put(slotKey, Records.part(part));
		references.retain(part.blobs());
		return part;
	}

	private static Optional<Slot> findUpload(final View view, final String bucket, final String key,
		final String uploadId) throws IOException, BlobStoreException {

		final OptionalLong sequence = Keys.sequence(uploadId);

		if (sequence.isEmpty()) {
			return Optional.empty();
		}

		final byte[] slotKey = Keys.upload(bucket, key, sequence.getAsLong());
		return view.get(slotKey).map(value -> new Slot(slotKey, value));
	}

	private static Slot requireUpload(final View view, final String bucket, final String key, final String uploadId)
		throws IOException, BlobStoreException, NoSuchUploadException {

		return findUpload(view, bucket, key, uploadId)
			.orElseThrow(() -> new NoSuchUploadException("No such upload"));
	}

	/* Deletes the upload with its parts and answers the parts in order */
	private static List<Part> dropUpload(final Transaction transaction, final References references,
		final Slot upload) throws IOException, BlobStoreException {

		final List<Part> parts = new ArrayList<>();

		for (Slot slot : slots(transaction, Keys.parts(Keys.parse(upload.key()).sequence()))) {
			final Part part = Records.part(slot.value());

			parts.add(part);
			transaction.delete(slot.key());
			references.release(part.blobs());
		}

		transaction.delete(upload.key());
		return parts;
	}

	private static byte[] afterKey(final byte kind, final String bucket, final ListQuery query) {
		return query.keyMarker().isEmpty() ? null : Keys.end(Keys.object(kind, bucket, query.keyMarker()));
	}

	private static byte[] resumeAfter(final Optional<Slot> resume, final byte kind, final String bucket,
		final ListQuery query) {

		return resume.isPresent() ? Keys.after(resume.get().key()) : afterKey(kind, bucket, query);
	}
}
