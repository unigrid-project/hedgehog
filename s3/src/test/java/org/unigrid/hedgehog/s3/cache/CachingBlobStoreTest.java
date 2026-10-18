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

package org.unigrid.hedgehog.s3.cache;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class CachingBlobStoreTest {
	private static final Duration HALF_LIFE = Duration.ofHours(1);
	private static final long ROOMY = 1L << 30;
	private static final int SIZE = 1000;

	private static final class Fixture implements AutoCloseable {
		private final FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix());
		private final Path directory = fileSystem.getPath("/cache");
		private final CountingBlobStore delegate = new CountingBlobStore();
		private final MutableClock clock = new MutableClock();

		private CachingBlobStore cache(final long budget) {
			return new CachingBlobStore(delegate, directory, budget, HALF_LIFE, clock);
		}

		/* Counted on disk rather than taken from the cache, which could be wrong about itself */
		private long cachedBytes() throws IOException {
			long bytes = 0;

			try (Stream<Path> files = Files.list(directory)) {
				for (Path file : files.toList()) {
					bytes += Files.size(file);
				}
			}

			return bytes;
		}

		/* Opening counts as a read, so ask last, once nothing else depends on the ranking */
		private boolean holds(final CachingBlobStore cache, final BlobId id) throws Exception {
			final int opens = delegate.opens();

			cache.open(id);
			return delegate.opens() == opens;
		}

		@Override
		public void close() throws IOException {
			fileSystem.close();
		}
	}

	private static byte[] random(final int size, final long seed) {
		final byte[] content = new byte[size];

		new Random(seed).nextBytes(content);
		return content;
	}

	private static BlobId store(final BlobStore store, final byte[] content) throws Exception {
		return store.store(new ByteArrayInputStream(content));
	}

	private static byte[] read(final Blob blob) throws Exception {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		blob.writeTo(output);
		return output.toByteArray();
	}

	private static byte[] read(final CacheLease lease) throws Exception {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		lease.writeTo(output, 0, lease.size());
		return output.toByteArray();
	}

	private static void open(final CachingBlobStore cache, final BlobId id, final int times) throws Exception {
		for (int i = 0; i < times; i++) {
			cache.open(id);
		}
	}

	@Property(tries = 100)
	public void readsTheRangesTheDelegateHolds(@ForAll @IntRange(max = 200_000) int size, @ForAll long seed,
		@ForAll @DoubleRange(max = 1) double start, @ForAll @DoubleRange(max = 1) double share) throws Exception {

		final byte[] content = random(size, seed);
		final int offset = (int) (start * size);
		final int length = (int) (share * (size - offset));
		final byte[] expected = Arrays.copyOfRange(content, offset, offset + length);

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final BlobId id = store(fixture.delegate, content);

			for (int i = 0; i < 2; i++) {
				final ByteArrayOutputStream output = new ByteArrayOutputStream();

				cache.open(id).writeTo(output, offset, length);
				assertThat(output.toByteArray(), equalTo(expected));
			}

			assertThat(fixture.delegate.opens(), equalTo(1));
		}
	}

	@Property(tries = 200)
	public void neverHoldsMoreThanItsBudget(@ForAll @IntRange(max = 4096) int budget,
		@ForAll @Size(min = 1, max = 20) List<@IntRange(max = 2048) Integer> sizes,
		@ForAll @Size(max = 60) List<@IntRange(max = 1000) Integer> reads) throws Exception {

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(budget);
			final List<BlobId> ids = new ArrayList<>();

			for (int i = 0; i < sizes.size(); i++) {
				ids.add(store(i % 2 == 0 ? cache : fixture.delegate, random(sizes.get(i), i)));
				assertThat(fixture.cachedBytes(), lessThanOrEqualTo((long) budget));
			}

			for (int read : reads) {
				final int index = read % ids.size();

				fixture.clock.advance(Duration.ofMinutes(read));
				assertThat(read(cache.open(ids.get(index))), equalTo(random(sizes.get(index), index)));
				assertThat(fixture.cachedBytes(), lessThanOrEqualTo((long) budget));
			}
		}
	}

	@Property
	public void keepsSmallOftenReadBlobsOverLargeRarelyReadOnes(@ForAll @IntRange(min = 1, max = SIZE) int small,
		@ForAll @IntRange(min = 1, max = SIZE) int larger, @ForAll @IntRange(min = 1, max = SIZE) int largeReads,
		@ForAll @IntRange(max = 20) int moreSmallReads) throws Exception {

		Assume.that(largeReads <= small);

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(2L * small + larger);
			final BlobId smallId = store(cache, new byte[small]);
			final BlobId largeId = store(cache, new byte[small + larger]);

			open(cache, smallId, largeReads + moreSmallReads - 1);
			open(cache, largeId, largeReads - 1);
			store(cache, new byte[1]);

			assertThat(fixture.holds(cache, smallId), equalTo(true));
			assertThat(fixture.holds(cache, largeId), equalTo(false));
		}
	}

	@Property
	public void letsOldReadsFadeBelowRecentOnes(@ForAll @IntRange(min = 2, max = 100) int oldReads,
		@ForAll @IntRange(min = 1, max = 10) int halfLives) throws Exception {

		final int recentReads = (int) (oldReads / Math.pow(2, halfLives)) + 1;

		Assume.that(recentReads < oldReads);

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(2 * SIZE);
			final BlobId old = store(cache, new byte[SIZE]);

			open(cache, old, oldReads - 1);
			fixture.clock.advance(HALF_LIFE.multipliedBy(halfLives));

			final BlobId recent = store(cache, new byte[SIZE]);

			open(cache, recent, recentReads - 1);
			store(cache, new byte[1]);

			assertThat(fixture.holds(cache, recent), equalTo(true));
			assertThat(fixture.holds(cache, old), equalTo(false));
		}
	}

	@Property(tries = 20)
	public void fetchesOnceForReadersMissingTogether(@ForAll @IntRange(max = 100_000) int size,
		@ForAll @IntRange(min = 2, max = 16) int readers) throws Exception {

		final byte[] content = random(size, size);

		try (Fixture fixture = new Fixture(); ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final BlobId id = store(fixture.delegate, content);
			final CountDownLatch arrivals = new CountDownLatch(readers);

			fixture.delegate.holdOpensFor(arrivals);

			final List<Future<byte[]>> reads = IntStream.range(0, readers).mapToObj(reader -> executor.submit(() -> {
				arrivals.countDown();
				return read(cache.open(id));
			})).toList();

			for (Future<byte[]> read : reads) {
				assertThat(read.get(), equalTo(content));
			}

			assertThat(fixture.delegate.opens(), equalTo(1));
		}
	}

	@Property
	public void servesWhatItStoredWithoutTheDelegate(@ForAll byte[] content) throws Exception {
		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final BlobId id = store(cache, content);

			assertThat(read(cache.open(id)), equalTo(content));
			assertThat(fixture.delegate.opens(), equalTo(0));
			assertThat(read(fixture.delegate.open(id)), equalTo(content));
		}
	}

	@Property
	public void leavesOutBlobsLargerThanTheWholeCache(@ForAll @Size(min = 1) byte[] content,
		@ForAll boolean writeThrough) throws Exception {

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(content.length - 1);
			final BlobId id = store(writeThrough ? cache : fixture.delegate, content);

			assertThat(read(cache.open(id)), equalTo(content));
			assertThat(fixture.cachedBytes(), equalTo(0L));
		}
	}

	@Property
	public void fetchesAgainWhatWasEvictedSinceItWasOpened(@ForAll @Size(min = 1, max = SIZE) byte[] content)
		throws Exception {

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(content.length);
			final Blob blob = cache.open(store(fixture.delegate, content));

			fixture.clock.advance(HALF_LIFE.multipliedBy(2));
			store(cache, new byte[content.length]);

			assertThat(read(blob), equalTo(content));
			assertThat(fixture.delegate.opens(), equalTo(2));
		}
	}

	@Property
	public void keepsALeasedBlobUntilTheLeaseIsClosed(@ForAll @Size(min = 1, max = SIZE) byte[] content,
		@ForAll @Size(min = 1, max = 10) List<@IntRange(min = 1, max = SIZE) Integer> others) throws Exception {

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(content.length);
			final BlobId id = store(fixture.delegate, content);

			try (CacheLease lease = ((CachedBlob) cache.open(id)).lease()) {
				for (int other : others) {
					store(cache, new byte[other]);
					assertThat(fixture.cachedBytes(), equalTo((long) content.length));
				}

				assertThat(read(lease), equalTo(content));
			}

			assertThat(fixture.holds(cache, id), equalTo(true));
		}
	}

	@Property
	public void picksUpWhatAnEarlierRunCached(@ForAll @Size(min = 1, max = 10) List<@Size(max = SIZE) byte[]> contents)
		throws Exception {

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final List<BlobId> ids = new ArrayList<>();

			for (byte[] content : contents) {
				ids.add(store(cache, content));
			}

			final Path partial = Files.write(fixture.directory.resolve("4711.partial"), new byte[] { 1 });
			final CachingBlobStore restarted = fixture.cache(ROOMY);

			for (int i = 0; i < ids.size(); i++) {
				assertThat(read(restarted.open(ids.get(i))), equalTo(contents.get(i)));
			}

			assertThat(fixture.delegate.opens(), equalTo(0));
			assertThat(Files.exists(partial), equalTo(false));
		}
	}

	@Property
	public void shrinksToANewBudgetOnRestart(@ForAll @Size(min = 1, max = 10) List<@IntRange(max = SIZE) Integer> sizes,
		@ForAll @IntRange(max = 2 * SIZE) int budget) throws Exception {

		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final List<BlobId> ids = new ArrayList<>();

			for (int size : sizes) {
				ids.add(store(cache, new byte[size]));
			}

			fixture.cache(budget).open(ids.getFirst());
			assertThat(fixture.cachedBytes(), lessThanOrEqualTo((long) budget));
		}
	}

	@Property
	public void forgetsADeletedBlob(@ForAll byte[] content) throws Exception {
		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final BlobId id = store(cache, content);

			cache.delete(id);
			assertThrows(BlobNotFoundException.class, () -> cache.open(id));
			assertThat(fixture.cachedBytes(), equalTo(0L));
		}
	}

	@Property
	public void letsReadersFinishABlobDeletedUnderThem(@ForAll byte[] content) throws Exception {
		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);
			final BlobId id = store(cache, content);

			try (CacheLease lease = ((CachedBlob) cache.open(id)).lease()) {
				cache.delete(id);
				assertThat(read(lease), equalTo(content));
				assertThat(fixture.cachedBytes(), equalTo((long) content.length));
			}

			assertThat(fixture.cachedBytes(), equalTo(0L));
		}
	}

	@Example
	public void cachesNothingForABlobTheDelegateLacks() throws Exception {
		try (Fixture fixture = new Fixture()) {
			final CachingBlobStore cache = fixture.cache(ROOMY);

			assertThrows(BlobNotFoundException.class, () -> cache.open(new BlobId("missing")));
			assertThat(fixture.cachedBytes(), equalTo(0L));
		}
	}
}
