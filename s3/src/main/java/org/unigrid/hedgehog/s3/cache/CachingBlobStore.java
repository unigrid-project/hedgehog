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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import lombok.Synchronized;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Keeps decoded blobs in local files within a byte budget, evicting the blobs read least for the room they take.
   Blobs never change, so a cached copy never goes stale. */
public final class CachingBlobStore implements BlobStore {
	private static final String PARTIAL = ".partial";
	private static final Pattern NAME = Pattern.compile("[0-9a-f]{64}");

	private final BlobStore delegate;
	private final Path directory;
	private final long budget;
	private final Duration halfLife;
	private final Clock clock;
	private final Map<String, CacheEntry> entries = new HashMap<>();
	private boolean indexed;
	private long bytes;

	private record Claim(CacheEntry entry, boolean fresh) { }

	public CachingBlobStore(final BlobStore delegate, final Path directory, final long budget,
		final Duration halfLife) {

		this(delegate, directory, budget, halfLife, Clock.systemUTC());
	}

	public CachingBlobStore(final BlobStore delegate, final Path directory, final long budget,
		final Duration halfLife, final Clock clock) {

		if (budget < 0 || !halfLife.isPositive()) {
			throw new IllegalArgumentException("A negative budget, or a half-life of zero or less");
		}

		this.delegate = delegate;
		this.directory = directory;
		this.budget = budget;
		this.halfLife = halfLife;
		this.clock = clock;
	}

	/* Ids grant read access, so only their hashes reach the file system */
	@SneakyThrows(NoSuchAlgorithmException.class)
	private static String name(final BlobId id) {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
			.digest(id.value().getBytes(StandardCharsets.UTF_8)));
	}

	private CacheEntry entry(final String name, final double hits) {
		return new CacheEntry(name, directory.resolve(name), new Popularity(hits, clock.instant()));
	}

	@Override
	public BlobId store(final InputStream input) throws IOException, BlobStoreException {
		final Path partial = partial();

		try {
			final TeeInputStream tee;
			final BlobId id;

			try (OutputStream copy = Files.newOutputStream(partial)) {
				tee = new TeeInputStream(input, copy, budget);
				id = delegate.store(tee);
			}

			if (tee.isComplete()) {
				admit(name(id), partial);
			}

			return id;
		} finally {
			Files.deleteIfExists(partial);
		}
	}

	@Override
	public Blob open(final BlobId id) throws IOException, BlobStoreException {
		final Claim claim = claim(name(id), false);
		final Optional<Blob> uncached = claim.fresh() ? load(claim.entry(), id, budget) : Optional.empty();

		try {
			return uncached.isPresent() ? uncached.get() : new CachedBlob(this, id, claim.entry().await());
		} catch (CancellationException ex) {
			return delegate.open(id);
		}
	}

	@Override
	public void delete(final BlobId id) throws IOException, BlobStoreException {
		try {
			delegate.delete(id);
		} finally {
			drop(name(id));
		}
	}

	CacheLease lease(final BlobId id) throws IOException, BlobStoreException {
		final Claim claim = claim(name(id), true);
		final CacheLease lease = new CacheLease(this, claim.entry());

		try {
			if (claim.fresh()) {
				load(claim.entry(), id, Long.MAX_VALUE);
			}

			claim.entry().await();
			return lease;
		} catch (Throwable ex) {
			lease.close();
			throw ex;
		}
	}

	@Synchronized
	void release(final CacheEntry entry) throws IOException {
		entry.unpin();
		vacate(entry);
		evict();
	}

	/* Whoever claims a fresh entry has to settle it one way or another, or those waiting on it would wait forever.
	   A blob larger than the whole budget is handed back uncached, and those waiting fetch it themselves. */
	private Optional<Blob> load(final CacheEntry entry, final BlobId id, final long limit)
		throws IOException, BlobStoreException {

		try {
			final Blob source = delegate.open(id);

			if (source.size() > limit) {
				abandon(entry);
				return Optional.of(source);
			}

			final Path partial = partial();

			try {
				try (OutputStream output = Files.newOutputStream(partial)) {
					source.writeTo(output);
				}

				admit(entry, partial);
			} finally {
				Files.deleteIfExists(partial);
			}

			return Optional.empty();
		} catch (Throwable ex) {
			fail(entry, ex);
			throw ex;
		}
	}

	@Synchronized
	private Claim claim(final String name, final boolean reading) throws IOException {
		final CacheEntry existing = index().get(name);
		final Claim claim = existing != null && (!reading || pin(existing)) ? new Claim(existing, false)
			: new Claim(register(name, reading), true);

		claim.entry().hit(clock.instant(), halfLife);
		return claim;
	}

	/* A file removed behind the cache's back drops its entry, and the blob is fetched again */
	private boolean pin(final CacheEntry entry) throws IOException {
		try {
			entry.pin();
			return true;
		} catch (NoSuchFileException ex) {
			discard(entry);
			return false;
		}
	}

	private CacheEntry register(final String name, final boolean reading) throws IOException {
		final CacheEntry entry = entry(name, 0);

		entries.put(name, entry);

		if (reading) {
			entry.pin();
		}

		return entry;
	}

	/* The index has to be built first, or building it would sweep up a partial file that is still being written */
	@Synchronized
	private Path partial() throws IOException {
		index();
		return Files.createTempFile(directory, null, PARTIAL);
	}

	@Synchronized
	private void admit(final String name, final Path partial) throws IOException {
		if (!index().containsKey(name)) {
			final CacheEntry entry = entry(name, 1);

			entries.put(name, entry);
			admit(entry, partial);
		}
	}

	/* An entry dropped while it loaded still settles its readers, but its file does not stay */
	@Synchronized
	private void admit(final CacheEntry entry, final Path partial) throws IOException {
		Files.move(partial, entry.getPath(), StandardCopyOption.ATOMIC_MOVE);
		entry.place(Files.size(entry.getPath()));

		if (entries.get(entry.getName()) == entry) {
			bytes += entry.getSize();
			evict();
		} else {
			vacate(entry);
		}
	}

	@Synchronized
	private void fail(final CacheEntry entry, final Throwable cause) throws IOException {
		if (entry.fail(cause)) {
			discard(entry);
		}
	}

	@Synchronized
	private void abandon(final CacheEntry entry) throws IOException {
		entry.abandon();
		discard(entry);
	}

	@Synchronized
	private void drop(final String name) throws IOException {
		final CacheEntry entry = index().get(name);

		if (entry != null) {
			discard(entry);
		}
	}

	private void discard(final CacheEntry entry) throws IOException {
		if (entries.remove(entry.getName(), entry)) {
			bytes -= entry.getSize();
		}

		vacate(entry);
	}

	/* Deletes the file of an entry no longer indexed once nobody reads it, unless a newer entry has taken over the
	   name. That one holds the very same bytes, since an id always names the same content. */
	private void vacate(final CacheEntry entry) throws IOException {
		if (entry.isIdle() && !entries.containsKey(entry.getName())) {
			Files.deleteIfExists(entry.getPath());
		}
	}

	/* Ranked by decayed hits per byte as they stand now, so small blobs read often outlast large ones read rarely */
	private void evict() throws IOException {
		if (bytes > budget) {
			final Instant now = clock.instant();
			final List<CacheEntry> ranked = entries.values().stream().filter(CacheEntry::isIdle)
				.map(entry -> Map.entry(entry, entry.score(now, halfLife)))
				.sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();

			for (Iterator<CacheEntry> victims = ranked.iterator(); bytes > budget && victims.hasNext();) {
				discard(victims.next());
			}
		}
	}

	/* Built on first use from the names and sizes on disk, without reading any cached content */
	private Map<String, CacheEntry> index() throws IOException {
		if (!indexed) {
			Files.createDirectories(directory);

			try (Stream<Path> files = Files.list(directory)) {
				for (Path file : files.toList()) {
					adopt(file);
				}
			}

			indexed = true;
			evict();
		}

		return entries;
	}

	/* Nothing is known of how often an earlier run read a blob, so each starts out as if read once */
	private void adopt(final Path file) throws IOException {
		final String name = file.getFileName().toString();

		if (name.endsWith(PARTIAL)) {
			Files.delete(file);
		} else if (NAME.matcher(name).matches()) {
			final CacheEntry entry = entry(name, 1);

			entry.place(Files.size(file));
			entries.put(name, entry);
			bytes += entry.getSize();
		}
	}
}
