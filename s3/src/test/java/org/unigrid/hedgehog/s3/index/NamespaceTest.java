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

import java.io.ByteArrayOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

/* Runs random mutations, flushes and restarts against both the namespace and a plain model of it, and holds every
   answer, every listing page and the final state of the namespace against the model */
public class NamespaceTest {
	private static final String NOWHERE = "nowhere";

	private static final List<ListQuery> FINAL_QUERIES = List.of(ListQuery.builder().limit(1000).build(),
		ListQuery.builder().limit(1).build(), ListQuery.builder().delimiter("/").limit(2).build(),
		ListQuery.builder().prefix("a").delimiter("/").limit(1).build());

	@FunctionalInterface
	interface Lister<T> {
		Listing<T> list(ListQuery query) throws Exception;
	}

	@FunctionalInterface
	interface ModelLister<T> {
		NamespaceModel.Page<T> list(ListQuery query);
	}

	interface Op {
		void run(Fixture fixture) throws Exception;
	}

	static final class Fixture {
		private final Path directory;
		private final FlakyBlobStore blobs = new FlakyBlobStore();
		private final MutableClock clock = new MutableClock();
		private final NamespaceConfig config = TreeTest.CONFIG.toBuilder().clock(clock).build();
		private final NamespaceModel model = new NamespaceModel();
		private final Set<BlobId> everReferenced = new HashSet<>();
		private Namespace namespace;

		Fixture(final FileSystem files) throws Exception {
			directory = files.getPath("/namespace");
			namespace = Namespace.open(directory, blobs, config);
		}

		void reopen() throws Exception {
			namespace.close();
			namespace = Namespace.open(directory, blobs, config);
		}

		void referenced(final Set<BlobId> blobs) {
			everReferenced.addAll(blobs);
		}

		/* No blob a version or part still refers to may ever have been deleted */
		void assertNothingLiveDeleted() {
			final Set<BlobId> deleted = new HashSet<>(blobs.deletions());

			deleted.retainAll(model.blobs());
			assertThat(deleted, equalTo(Set.of()));
		}

		/* Once the grace period is over, every blob nothing refers to any more is gone, and of the index itself only
		   the manifest the root names and its runs are left */
		void assertCollected() throws Exception {
			namespace.flush();
			namespace.compact();
			clock.advance(config.getGrace().plusSeconds(1));
			namespace.maintain();

			final Set<BlobId> released = new HashSet<>(everReferenced);
			final BlobId root = namespace.root().orElseThrow();
			final Set<BlobId> index = new HashSet<>(Set.of(root));

			released.removeAll(model.blobs());
			Manifest.decode(read(root)).runs().forEach(run -> index.add(run.id()));
			assertNothingLiveDeleted();
			assertThat(blobs.deletions().containsAll(released), equalTo(true));
			assertThat(blobs.stored(), equalTo(index));
		}

		private byte[] read(final BlobId id) throws Exception {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

			blobs.open(id).writeTo(bytes);
			return bytes.toByteArray();
		}

		Optional<Bucket> bucket(final String name) {
			return model.bucket(name);
		}

		boolean versioned(final String bucket) {
			return model.bucket(bucket).orElseThrow().versioning() == Versioning.ENABLED;
		}

		<T> void assertPages(final Lister<T> actual, final ModelLister<T> expected, final ListQuery query)
			throws Exception {

			ListQuery current = query;

			for (boolean more = true; more;) {
				final Listing<T> page = actual.list(current);
				final NamespaceModel.Page<T> modelled = expected.list(current);

				assertThat(page.entries(), equalTo(modelled.entries()));
				assertThat(page.commonPrefixes(), equalTo(modelled.prefixes()));
				assertThat(page.next(), equalTo(modelled.next()));
				more = page.next().isPresent();

				if (more) {
					current = current.toBuilder().keyMarker(page.next().get().key())
						.idMarker(Objects.requireNonNullElse(page.next().get().id(), "")).build();
				}
			}
		}

		void verify() throws Exception {
			assertThat(namespace.buckets(), equalTo(model.buckets()));

			for (Bucket bucket : model.buckets()) {
				for (List<ObjectVersion> versions : model.versions(bucket.name()).values()) {
					final ObjectVersion latest = versions.getFirst();

					assertThat(namespace.latest(latest.bucket(), latest.key()),
						equalTo(Optional.of(latest)));

					for (ObjectVersion version : versions) {
						assertThat(namespace.version(version.bucket(), version.key(),
							version.versionId()), equalTo(Optional.of(version)));
					}
				}

				for (ListQuery query : FINAL_QUERIES) {
					assertPages(page -> namespace.listObjects(bucket.name(), page),
						page -> model.listObjects(bucket.name(), page), query);
					assertPages(page -> namespace.listVersions(bucket.name(), page),
						page -> model.listVersions(bucket.name(), page), query);
					assertPages(page -> namespace.listUploads(bucket.name(), page),
						page -> model.listUploads(bucket.name(), page), query);
				}
			}

			for (Upload upload : model.uploads()) {
				assertThat(namespace.upload(upload.bucket(), upload.key(), upload.uploadId()),
					equalTo(Optional.of(upload)));
			}
		}
	}

	record CreateBucket(String bucket) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Bucket created = Bucket.builder().name(bucket).created(Instant.EPOCH).build();

			assertThat(fixture.namespace.createBucket(created), equalTo(fixture.bucket(bucket).isEmpty()));

			if (fixture.bucket(bucket).isEmpty()) {
				fixture.model.putBucket(created);
			}
		}
	}

	record DeleteBucket(String bucket) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			if (fixture.bucket(bucket).isEmpty()) {
				assertThrows(NoSuchBucketException.class, () -> fixture.namespace.deleteBucket(bucket));
			} else if (!fixture.model.isEmpty(bucket)) {
				assertThrows(BucketNotEmptyException.class, () -> fixture.namespace.deleteBucket(bucket));
			} else {
				fixture.namespace.deleteBucket(bucket);
				fixture.model.removeBucket(bucket);
			}
		}
	}

	record SetVersioning(String bucket, Versioning state) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			if (fixture.bucket(bucket).isEmpty()) {
				assertThrows(NoSuchBucketException.class, () -> fixture.namespace.updateBucket(bucket,
					current -> current));
			} else {
				final Bucket changed = fixture.namespace.updateBucket(bucket, current -> current.toBuilder()
					.versioning(state).build());

				final Bucket expected = fixture.bucket(bucket).orElseThrow().toBuilder().versioning(state)
					.build();

				assertThat(changed, equalTo(expected));
				fixture.model.putBucket(changed);
			}
		}
	}

	record Put(String bucket, String key, ObjectVersion content, boolean onlyIfAbsent) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final boolean exists = fixture.model.latest(bucket, key).filter(latest -> !latest.deleteMarker())
				.isPresent();

			if (fixture.bucket(bucket).isEmpty()) {
				assertThrows(NoSuchBucketException.class, () -> fixture.namespace.put(content,
					latest -> true));
			} else if (onlyIfAbsent && exists) {
				assertThrows(ConditionFailedException.class, () -> fixture.namespace.put(content,
					latest -> latest.filter(version -> !version.deleteMarker()).isEmpty()));
			} else {
				final ObjectVersion stored = fixture.namespace.put(content, latest -> true);

				assertThat(stored, equalTo(content.toBuilder().versionId(stored.versionId()).build()));
				assertThat(stored.isNullVersion(), equalTo(!fixture.versioned(bucket)));
				fixture.referenced(stored.blobs());
				fixture.model.put(stored);
			}
		}
	}

	/* Copies the newest version of a key by reference, so both keys share its blobs */
	record Copy(String bucket, String source, String target) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Optional<ObjectVersion> found = fixture.model.latest(bucket, source)
				.filter(version -> !version.deleteMarker());

			if (found.isPresent()) {
				new Put(bucket, target, found.get().toBuilder().key(target).versionId(null).build(), false)
					.run(fixture);
			}
		}
	}

	record Delete(String bucket, String key, Instant at) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			if (fixture.bucket(bucket).isEmpty()) {
				assertThrows(NoSuchBucketException.class, () -> fixture.namespace.delete(bucket, key, at));
			} else if (fixture.bucket(bucket).orElseThrow().versioning() == Versioning.UNVERSIONED) {
				assertThat(fixture.namespace.delete(bucket, key, at), equalTo(Optional.empty()));
				fixture.model.remove(bucket, key, ObjectVersion.NULL_VERSION);
			} else {
				final ObjectVersion marker = fixture.namespace.delete(bucket, key, at).orElseThrow();

				assertThat(marker, equalTo(ObjectVersion.builder().bucket(bucket).key(key).deleteMarker(true)
					.lastModified(at).versionId(marker.versionId()).build()));
				assertThat(marker.isNullVersion(), equalTo(!fixture.versioned(bucket)));
				fixture.model.put(marker);
			}
		}
	}

	record Remove(String bucket, String key, int pick, boolean allowed) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final String id = fixture.model.pickVersion(bucket, key, pick);
			final Optional<ObjectVersion> expected = fixture.model.version(bucket, key, id);

			if (expected.isPresent() && !allowed) {
				assertThrows(ConditionFailedException.class, () -> fixture.namespace.remove(bucket, key, id,
					version -> false));
			} else {
				assertThat(fixture.namespace.remove(bucket, key, id, version -> true), equalTo(expected));
				fixture.model.remove(bucket, key, id);
			}
		}
	}

	record Tag(String bucket, String key, int pick, Map<String, String> tags) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final String id = fixture.model.pickVersion(bucket, key, pick);
			final Optional<ObjectVersion> expected = fixture.model.version(bucket, key, id)
				.map(version -> version.toBuilder().tags(tags).build());

			assertThat(fixture.namespace.update(bucket, key, id,
				version -> version.toBuilder().tags(tags).build()), equalTo(expected));

			expected.ifPresent(fixture.model::replace);
		}
	}

	record CreateUpload(String bucket, String key, ObjectVersion template) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Upload draft = new Upload(null, template, "CRC32C", "COMPOSITE");

			if (fixture.bucket(bucket).isEmpty()) {
				assertThrows(NoSuchBucketException.class, () -> fixture.namespace.createUpload(draft));
			} else {
				final Upload upload = fixture.namespace.createUpload(draft);

				assertThat(upload, equalTo(draft.toBuilder().uploadId(upload.uploadId()).build()));
				assertThat(upload.uploadId(), not(equalTo(null)));
				fixture.model.addUpload(upload);
			}
		}
	}

	record PutPart(int pick, Part part) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Optional<Upload> upload = fixture.model.pickUpload(pick);

			if (upload.isEmpty()) {
				assertThrows(NoSuchUploadException.class, () -> fixture.namespace.putPart(NOWHERE, NOWHERE,
					Keys.id(1), part));
			} else {
				assertThat(fixture.namespace.putPart(upload.get().bucket(), upload.get().key(),
					upload.get().uploadId(), part), equalTo(part));

				fixture.referenced(part.blobs());
				fixture.model.parts(upload.get().uploadId()).put(part.number(), part);
			}
		}
	}

	record Complete(int pick) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Optional<Upload> found = fixture.model.pickUpload(pick);

			if (found.isEmpty()) {
				assertThrows(NoSuchUploadException.class, () -> fixture.namespace.completeUpload("a",
					NOWHERE, Keys.id(1), (upload, parts) -> upload.template(), latest -> true));
			} else {
				final Upload upload = found.get();
				final List<Part> parts = List.copyOf(fixture.model.parts(upload.uploadId()).values());
				final ObjectVersion assembled = upload.template().toBuilder().parts(parts)
					.etag("multipart-" + parts.size()).build();
				final ObjectVersion stored = fixture.namespace.completeUpload(upload.bucket(), upload.key(),
					upload.uploadId(), (given, listed) -> {
						assertThat(given, equalTo(upload));
						assertThat(listed, equalTo(parts));
						return assembled;
					}, latest -> true);

				assertThat(stored, equalTo(assembled.toBuilder().versionId(stored.versionId()).build()));

				fixture.model.dropUpload(upload);
				fixture.model.put(stored);
			}
		}
	}

	record Abort(int pick) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Optional<Upload> found = fixture.model.pickUpload(pick);

			if (found.isEmpty()) {
				assertThrows(NoSuchUploadException.class, () -> fixture.namespace.abortUpload(NOWHERE,
					NOWHERE, Keys.id(1)));
			} else {
				final Upload upload = found.get();

				fixture.namespace.abortUpload(upload.bucket(), upload.key(), upload.uploadId());
				fixture.model.dropUpload(upload);
				assertThat(fixture.namespace.upload(upload.bucket(), upload.key(), upload.uploadId()),
					equalTo(Optional.empty()));
			}
		}
	}

	record Compact() implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.namespace.compact();
		}
	}

	/* Lets time pass and maintains, which may flush, compact and delete what the grace period no longer protects */
	record Maintain(Duration elapsed) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.clock.advance(elapsed);
			fixture.namespace.maintain();
			fixture.assertNothingLiveDeleted();
		}
	}

	record Flush() implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.namespace.flush();
		}
	}

	record Reopen() implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.reopen();
		}
	}

	record ListObjects(String bucket, ListQuery query) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.assertPages(page -> fixture.namespace.listObjects(bucket, page),
				page -> fixture.model.listObjects(bucket, page), query);
		}
	}

	record ListVersions(String bucket, ListQuery query) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.assertPages(page -> fixture.namespace.listVersions(bucket, page),
				page -> fixture.model.listVersions(bucket, page), query);
		}
	}

	record ListUploads(String bucket, ListQuery query) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			fixture.assertPages(page -> fixture.namespace.listUploads(bucket, page),
				page -> fixture.model.listUploads(bucket, page), query);
		}
	}

	record ListParts(int pick, int limit) implements Op {
		@Override
		public void run(final Fixture fixture) throws Exception {
			final Optional<Upload> found = fixture.model.pickUpload(pick);

			if (found.isPresent()) {
				final Upload upload = found.get();

				fixture.assertPages(page -> fixture.namespace.parts(upload.bucket(), upload.key(),
					upload.uploadId(), marker(page), page.limit()),
					page -> fixture.model.listParts(upload, marker(page), page.limit()),
					ListQuery.builder().limit(limit).build());
			}
		}

		private static int marker(final ListQuery query) {
			return query.idMarker().isEmpty() ? 0 : Integer.parseInt(query.idMarker());
		}
	}

	private FileSystem files;

	@BeforeTry
	void openFiles() {
		files = PosixFiles.create();
	}

	@AfterTry
	void closeFiles() throws Exception {
		files.close();
	}

	@Provide
	Arbitrary<String> buckets() {
		return Arbitraries.of("a", "b");
	}

	/* Mostly a handful of keys so versions pile up and directories fill, now and then any key at all */
	@Provide
	Arbitrary<String> keys() {
		return Arbitraries.frequencyOf(
			Tuple.of(4, Arbitraries.of("a", "b", "ab", "a/b", "a/c", "a/b/c", "b/a", "a.b")),
			Tuple.of(1,IndexArbitraries.texts(4).filter(key -> !key.isEmpty())));
	}

	@Provide
	Arbitrary<ListQuery> queries() {
		final Arbitrary<String> prefixes = Arbitraries.oneOf(Arbitraries.of("", "", "a", "a/", "b", "/", "a/b"),
			IndexArbitraries.texts(2));
		final Arbitrary<String> markers = Arbitraries.oneOf(Arbitraries.just(""), Arbitraries.just(""), keys());

		return Combinators.combine(prefixes, Arbitraries.of("", "/", ".", "a/", "b"), markers,
			Arbitraries.integers().between(1, 4)).as((prefix, delimiter, marker, limit) -> new ListQuery(prefix,
			delimiter, marker, "", limit));
	}

	private Arbitrary<ObjectVersion> contents(final String bucket, final String key) {
		return IndexArbitraries.versions(bucket, key, null).map(version -> version.toBuilder().deleteMarker(false)
			.build());
	}

	private Arbitrary<Op> puts() {
		return Combinators.combine(buckets(), keys(), Arbitraries.of(true, false, false, false))
			.flatAs((bucket, key, onlyIfAbsent) -> contents(bucket, key)
			.map(content -> new Put(bucket, key, content, onlyIfAbsent)));
	}

	private Arbitrary<Op> mutations() {
		final Arbitrary<Integer> picks = Arbitraries.integers();

		return Arbitraries.frequencyOf(
			Tuple.of(2, buckets().map(CreateBucket::new)),
			Tuple.of(1, buckets().map(DeleteBucket::new)),
			Tuple.of(3, Combinators.combine(buckets(), Arbitraries.of(Versioning.class)).as(SetVersioning::new)),
			Tuple.of(20, puts()),
			Tuple.of(3, Combinators.combine(buckets(), keys(), IndexArbitraries.instants()).as(Delete::new)),
			Tuple.of(3, Combinators.combine(buckets(), keys(), picks, Arbitraries.of(true, true, false))
				.as(Remove::new)),
			Tuple.of(1, Combinators.combine(buckets(), keys(), picks, IndexArbitraries.maps()).as(Tag::new)),
			Tuple.of(2, Combinators.combine(buckets(), keys()).flatAs((bucket, key) -> contents(bucket, key)
				.map(template -> new CreateUpload(bucket, key, template.toBuilder().parts(null).build())))),
			Tuple.of(3, Combinators.combine(picks, IndexArbitraries.parts()).as(PutPart::new)),
			Tuple.of(1, picks.map(Complete::new)),
			Tuple.of(1, picks.map(Abort::new)),
			Tuple.of(3, Combinators.combine(buckets(), keys(), keys()).as(Copy::new))
		);
	}

	private Arbitrary<Op> checks() {
		return Arbitraries.frequencyOf(
			Tuple.of(1, Arbitraries.just(new Flush())),
			Tuple.of(1, Arbitraries.just(new Reopen())),
			Tuple.of(1, Arbitraries.just(new Compact())),
			Tuple.of(2, Arbitraries.longs().between(0, 2 * TreeTest.CONFIG.getGrace().toSeconds())
				.map(seconds -> new Maintain(Duration.ofSeconds(seconds)))),
			Tuple.of(2, Combinators.combine(buckets(), queries()).as(ListObjects::new)),
			Tuple.of(2, Combinators.combine(buckets(), queries()).as(ListVersions::new)),
			Tuple.of(1, Combinators.combine(buckets(), queries()).as(ListUploads::new)),
			Tuple.of(1, Combinators.combine(Arbitraries.integers(), Arbitraries.integers().between(1, 3))
				.as(ListParts::new))
		);
	}

	@Provide
	Arbitrary<List<Op>> operations() {
		return Arbitraries.frequencyOf(Tuple.of(5, mutations()), Tuple.of(1, checks())).list().ofMaxSize(60)
			.map(ops -> Stream.concat(Stream.of(new CreateBucket("a")), ops.stream()).toList());
	}

	@Property(tries = 300)
	public void behavesLikeTheModel(@ForAll("operations") final List<Op> operations) throws Exception {
		final Fixture fixture = new Fixture(files);

		try {
			for (Op operation : operations) {
				operation.run(fixture);
			}

			fixture.verify();
			fixture.reopen();
			fixture.verify();
			fixture.assertCollected();
			fixture.reopen();
			fixture.verify();
		} finally {
			fixture.namespace.close();
		}
	}
}
