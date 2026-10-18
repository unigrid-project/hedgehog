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
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* The buckets, object versions and multipart uploads of one S3 namespace, kept in a tree of immutable blobs that a
   root blob id names. Every mutation is durable in the local journal before it returns and atomic: guards,
   changes and assembly functions run under the writer lock against the current state, and when one throws or a
   guard fails nothing is written. Reads see a consistent snapshot and never wait for writers. One node writes a
   namespace at a time; several writers on one namespace, and anchoring its root anywhere but the local root file,
   are left for later. */
public final class Namespace implements AutoCloseable {
	private final Tree tree;

	private Namespace(final Tree tree) {
		this.tree = tree;
	}

	public static Namespace open(final Path directory, final BlobStore blobs, final NamespaceConfig config)
		throws IOException, BlobStoreException {

		return new Namespace(Tree.open(directory, blobs, config, null));
	}

	/* Mounts the namespace a root names, such as on a fresh node recovering the namespace of a lost one */
	public static Namespace mount(final Path directory, final BlobStore blobs, final BlobId root,
		final NamespaceConfig config) throws IOException, BlobStoreException {

		return new Namespace(Tree.open(directory, blobs, config, Objects.requireNonNull(root)));
	}

	/* The manifest the local root names, whose id grants read access to the whole namespace */
	public Optional<BlobId> root() {
		return tree.root();
	}

	public boolean createBucket(final Bucket bucket) throws IOException, BlobStoreException {
		return tree.write(transaction -> {
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

		return tree.write(transaction -> {
			final Bucket changed = change.apply(requireBucket(transaction, name));

			if (!changed.name().equals(name)) {
				throw new IllegalArgumentException("A bucket keeps its name");
			}

			transaction.put(Keys.bucket(name), Records.bucket(changed));
			return changed;
		});
	}

	public void deleteBucket(final String name) throws IOException, BlobStoreException, NamespaceException {
		tree.write(transaction -> {
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

		return tree.write(transaction -> {
			final Bucket bucket = requireBucket(transaction, version.bucket());

			check(guard.test(latest(transaction, version.bucket(), version.key())));
			return store(transaction, bucket, version);
		});
	}

	/* Deletes the way a delete without a version id does: an unversioned bucket drops the null version, and any
	   other bucket stores a delete marker, which the answer holds */
	public Optional<ObjectVersion> delete(final String bucket, final String key, final Instant at)
		throws IOException, BlobStoreException, NoSuchBucketException {

		return tree.write(transaction -> {
			final Bucket found = requireBucket(transaction, bucket);

			if (found.versioning() == Versioning.UNVERSIONED) {
				findNullVersion(transaction, bucket, key).ifPresent(slot -> transaction.delete(slot.key()));
				return Optional.empty();
			}

			return Optional.of(store(transaction, found, ObjectVersion.builder().bucket(bucket).key(key)
				.deleteMarker(true).lastModified(at).build()));
		});
	}

	/* Removes one version for good once the guard accepts it, and answers the version removed */
	public Optional<ObjectVersion> remove(final String bucket, final String key, final String versionId,
		final Predicate<ObjectVersion> guard) throws IOException, BlobStoreException, ConditionFailedException {

		return tree.write(transaction -> {
			final Optional<Slot> found = findVersion(transaction, bucket, key, versionId);

			if (found.isEmpty()) {
				return Optional.empty();
			}

			final ObjectVersion version = version(found.get());

			check(guard.test(version));
			transaction.delete(found.get().key());
			return Optional.of(version);
		});
	}

	/* Changes what a version carries besides its identity and content, such as its tags, retention or legal hold */
	public Optional<ObjectVersion> update(final String bucket, final String key, final String versionId,
		final UnaryOperator<ObjectVersion> change) throws IOException, BlobStoreException {

		return tree.write(transaction -> {
			final Optional<Slot> found = findVersion(transaction, bucket, key, versionId);

			if (found.isEmpty()) {
				return Optional.empty();
			}

			final ObjectVersion current = version(found.get());
			final ObjectVersion changed = change.apply(current);

			if (!identity(current).equals(identity(changed))) {
				throw new IllegalArgumentException("An update keeps the identity and content of a version");
			}

			transaction.put(found.get().key(), Records.version(changed));
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

	public Upload createUpload(final Upload upload) throws IOException, BlobStoreException, NoSuchBucketException {
		return tree.write(transaction -> {
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

		return tree.write(transaction -> {
			final long upload = Keys.parse(requireUpload(transaction, bucket, key, uploadId).key()).sequence();

			transaction.put(Keys.part(upload, part.number()), Records.part(part));
			return part;
		});
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

		return tree.write(transaction -> {
			final Slot upload = requireUpload(transaction, bucket, key, uploadId);
			final Bucket found = requireBucket(transaction, bucket);

			check(guard.test(latest(transaction, bucket, key)));

			final ObjectVersion version = assembly.apply(upload(upload), dropUpload(transaction, upload));

			if (!version.bucket().equals(bucket) || !version.key().equals(key)) {
				throw new IllegalArgumentException("An upload completes as a version of its own key");
			}

			return store(transaction, found, version);
		});
	}

	public void abortUpload(final String bucket, final String key, final String uploadId) throws IOException,
		BlobStoreException, NoSuchUploadException {

		tree.write(transaction -> dropUpload(transaction, requireUpload(transaction, bucket, key, uploadId)));
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

	@Override
	public void close() throws IOException {
		tree.close();
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

	private static ObjectVersion store(final Transaction transaction, final Bucket bucket,
		final ObjectVersion version) throws IOException, BlobStoreException {

		final boolean versioned = bucket.versioning() == Versioning.ENABLED;

		if (!versioned) {
			findNullVersion(transaction, bucket.name(), version.key())
				.ifPresent(slot -> transaction.delete(slot.key()));
		}

		final ObjectVersion stored = version.toBuilder().versionId(versioned ? Keys.id(transaction.sequence())
			: ObjectVersion.NULL_VERSION).build();

		transaction.put(Keys.version(bucket.name(), version.key(), transaction.sequence()), Records.version(stored));
		return stored;
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
	private static List<Part> dropUpload(final Transaction transaction, final Slot upload) throws IOException,
		BlobStoreException {

		final List<Part> parts = new ArrayList<>();

		for (Slot slot : slots(transaction, Keys.parts(Keys.parse(upload.key()).sequence()))) {
			parts.add(Records.part(slot.value()));
			transaction.delete(slot.key());
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
