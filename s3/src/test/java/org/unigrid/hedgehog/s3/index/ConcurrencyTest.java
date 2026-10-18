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

import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.s3.spi.BlobId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/* Writers, readers and maintenance at once: a reader must only ever see whole mutations, while flushes and
   compactions swap the runs under it */
public class ConcurrencyTest {
	private static final byte[] FIRST = {1};
	private static final byte[] SECOND = {2};
	private static final byte[] THIRD = {3};
	private static final long ABSENT = -1;
	private static final int WRITES = 2000;
	private static final int WRITERS = 4;
	private static final int OBJECTS = 150;
	private static final String BUCKET = "bucket";
	private static final String HOT = "hot";
	private static final Duration PATIENCE = Duration.ofSeconds(20);

	private static byte[] number(final long value) {
		return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
	}

	private static long number(final Optional<byte[]> value) {
		return value.map(bytes -> ByteBuffer.wrap(bytes).getLong()).orElse(ABSENT);
	}

	/* Every write sets two keys to the same number and a third to it or to nothing, so a reader seeing the two
	   differ, or the third disagree with them, saw half a mutation */
	private static void write(final Tree tree, final long value) throws Exception {
		tree.write(transaction -> {
			transaction.put(FIRST, number(value));
			transaction.put(SECOND, number(value));

			if (value % 3 == 0) {
				transaction.delete(THIRD);
			} else {
				transaction.put(THIRD, number(value));
			}

			return null;
		});
	}

	private static long read(final Tree tree) throws Exception {
		final Snapshot snapshot = tree.snapshot();
		final long first = number(snapshot.get(FIRST));
		final long third = number(snapshot.get(THIRD));
		final List<Slot> scanned = RunTest.drain(snapshot.scan(new byte[0], null));

		assertThat(number(snapshot.get(SECOND)), equalTo(first));
		assertThat(third, equalTo(first % 3 == 0 ? ABSENT : first));
		assertThat(scanned.stream().map(slot -> number(Optional.of(slot.value()))).distinct().count() <= 1,
			equalTo(true));

		return first;
	}

	@Example
	public void readersSeeWholeMutationsWhileRunsAreSwapped() throws Exception {
		final AtomicBoolean done = new AtomicBoolean();
		final ExecutorService threads = Executors.newFixedThreadPool(4);

		try (FileSystem files = PosixFiles.create(); Tree tree = Tree.open(files.getPath("/namespace"),
			new FlakyBlobStore(), TreeTest.CONFIG, null)) {

			final Callable<Long> reader = () -> {
				long reads = 0;

				for (long seen = ABSENT; !done.get(); reads++) {
					final long now = read(tree);

					assertThat(now >= seen, equalTo(true));
					seen = now;
				}

				return reads;
			};

			final List<Future<Long>> readers = List.of(threads.submit(reader), threads.submit(reader));
			final Future<?> maintainer = threads.submit(() -> {
				while (!done.get()) {
					tree.flush();
					tree.compact(false);
				}

				return null;
			});

			for (long value = 1; value <= WRITES; value++) {
				write(tree, value);
			}

			done.set(true);
			maintainer.get();

			for (Future<Long> each : readers) {
				assertThat(each.get(), greaterThan(0L));
			}

			assertThat(read(tree), equalTo((long) WRITES));
		} finally {
			threads.shutdownNow();
		}
	}

	@Example
	public void writersInParallelAllLandAndTheBackgroundFlushesThem() throws Exception {
		final NamespaceConfig config = TreeTest.CONFIG.toBuilder().maintenanceInterval(Duration.ofMillis(5))
			.flushInterval(Duration.ofMillis(20)).flushBytes(4096).build();
		final FlakyBlobStore blobs = new FlakyBlobStore();
		final ExecutorService threads = Executors.newFixedThreadPool(WRITERS + 1);
		final AtomicBoolean done = new AtomicBoolean();
		final Set<String> expected = new HashSet<>(Set.of(HOT));

		try (FileSystem files = PosixFiles.create()) {
			try (Namespace namespace = Namespace.open(files.getPath("/namespace"), blobs, config)) {
				namespace.createBucket(Bucket.builder().name(BUCKET).created(Instant.EPOCH).build());

				final List<Future<?>> writers = new ArrayList<>();

				for (int writer = 0; writer < WRITERS; writer++) {
					final String prefix = "w" + writer + "/";

					writers.add(threads.submit(() -> writeObjects(namespace, prefix)));

					for (int i = 0; i < OBJECTS; i++) {
						expected.add(prefix + i);
					}
				}

				final Future<?> reader = threads.submit(() -> {
					final ListQuery hot = ListQuery.builder().prefix(HOT).limit(10).build();

					while (!done.get()) {
						assertThat(namespace.listVersions(BUCKET, hot).entries().size(),
							lessThanOrEqualTo(1));
					}

					return null;
				});

				for (Future<?> writer : writers) {
					writer.get();
				}

				done.set(true);
				reader.get();
				assertThat(keys(namespace), equalTo(expected));
				assertThat(awaitMounted(files, blobs, expected), equalTo(expected));
			}
		} finally {
			threads.shutdownNow();
		}
	}

	private static Object writeObjects(final Namespace namespace, final String prefix) throws Exception {
		for (int i = 0; i < OBJECTS; i++) {
			for (String key : List.of(prefix + i, HOT)) {
				namespace.put(ObjectVersion.builder().bucket(BUCKET).key(key).lastModified(Instant.EPOCH)
					.build(), latest -> true);
			}
		}

		return null;
	}

	private static Set<String> keys(final Namespace namespace) throws Exception {
		final Set<String> keys = new HashSet<>();

		namespace.listObjects(BUCKET, ListQuery.builder().limit(Integer.MAX_VALUE).build()).entries()
			.forEach(version -> keys.add(version.key()));

		return keys;
	}

	/* Mounts the root the background maintenance last wrote on a fresh node until that holds every write */
	private static Set<String> awaitMounted(final FileSystem files, final FlakyBlobStore blobs,
		final Set<String> expected) throws Exception {

		final Instant deadline = Instant.now().plus(PATIENCE);
		final Path root = files.getPath("/namespace", Tree.ROOT);
		Set<String> mounted = Set.of();

		for (int attempt = 0; !mounted.equals(expected) && Instant.now().isBefore(deadline); attempt++) {
			if (Files.exists(root)) {
				try (Namespace fresh = Namespace.mount(files.getPath("/mounted" + attempt), blobs,
					new BlobId(Files.readString(root)), TreeTest.CONFIG)) {

					mounted = keys(fresh);
				}
			}
		}

		return mounted;
	}
}
