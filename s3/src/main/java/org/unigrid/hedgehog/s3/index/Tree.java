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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.unigrid.hedgehog.s3.spi.Blob;
import org.unigrid.hedgehog.s3.spi.BlobId;
import org.unigrid.hedgehog.s3.spi.BlobNotFoundException;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* A log-structured merge tree whose runs and manifests are immutable blobs. A write goes to the journal and the
   memtable. A flush freezes the memtable, stores it as a run, stores a manifest listing every run and then points
   the local root file at that manifest, and only then drops the frozen journal, so a crash at any point leaves
   either the old root with the journal that rebuilds the rest or the new root. Writers queue on one lock, flushes
   and compactions on another, and readers take the current snapshot without locking at all. */
final class Tree implements Closeable {
	static final String ROOT = "root";

	private static final byte[] FIRST_KEY = new byte[0];

	@FunctionalInterface
	interface Work<T, E extends Exception> {
		T run(Transaction transaction) throws IOException, BlobStoreException, E;
	}

	private record Head(Manifest manifest, BlobId root) {
	}

	private final Path directory;
	private final BlobStore blobs;
	private final NamespaceConfig config;
	private final PageCache cache;
	private final Journal journal;
	private final ReentrantLock writer = new ReentrantLock();
	private final ReentrantLock maintainer = new ReentrantLock();
	private final Set<BlobId> swept = new HashSet<>();
	private volatile Snapshot snapshot;
	private volatile Head head;
	private volatile Instant dirtySince;

	private Tree(final Path directory, final BlobStore blobs, final NamespaceConfig config, final Journal journal,
		final Memtable memtable, final long sequence, final Head head) {

		this.directory = directory;
		this.blobs = blobs;
		this.config = config;
		this.journal = journal;
		this.head = head;
		cache = new PageCache(config.getCacheBytes());
		snapshot = new Snapshot(List.of(memtable), runs(head.manifest()), sequence);
		dirtySince = memtable.isEmpty() ? null : config.getClock().instant();
	}

	/* Opens the namespace the local root names, or mounts the one a root blob id names when given one */
	static Tree open(final Path directory, final BlobStore blobs, final NamespaceConfig config, final BlobId mount)
		throws IOException, BlobStoreException {

		Files.createDirectories(directory);

		final Optional<BlobId> root = Objects.isNull(mount) ? readRoot(directory) : adopt(directory, mount);
		final Manifest manifest = root.isPresent() ? Manifest.decode(read(blobs, root.get(), config))
			: Manifest.EMPTY;
		final Memtable memtable = new Memtable();
		final AtomicLong sequence = new AtomicLong(manifest.sequence());
		final Journal journal = Journal.open(directory, (number, batch) -> {
			memtable.apply(number, batch);
			sequence.accumulateAndGet(number, Math::max);
		});

		return new Tree(directory, blobs, config, journal, memtable, sequence.get(),
			new Head(manifest, root.orElse(null)));
	}

	private static Optional<BlobId> readRoot(final Path directory) throws IOException {
		final Path root = directory.resolve(ROOT);

		if (Files.exists(root)) {
			return Optional.of(new BlobId(Files.readString(root, StandardCharsets.UTF_8)));
		}

		return Optional.empty();
	}

	/* Mounting replaces the local root, which would strand the writes of another namespace still in the journal */
	private static Optional<BlobId> adopt(final Path directory, final BlobId mount) throws IOException {
		if (!readRoot(directory).equals(Optional.of(mount))) {
			final Path active = directory.resolve(Journal.ACTIVE);
			final boolean frozen = Files.exists(directory.resolve(Journal.FROZEN));

			if (frozen || Files.exists(active) && Files.size(active) > 0) {
				throw new IllegalStateException("The directory holds unflushed writes of another namespace");
			}

			LocalFiles.replace(directory.resolve(ROOT), mount.value().getBytes(StandardCharsets.UTF_8));
		}

		return Optional.of(mount);
	}

	/* A mounted root may name any blob, so its size is checked before it is read into memory */
	private static byte[] read(final BlobStore blobs, final BlobId id, final NamespaceConfig config)
		throws IOException, BlobStoreException {

		final Blob blob = blobs.open(id);

		if (blob.size() > config.getMaxBlockBytes()) {
			throw new CorruptIndexException("The root names a blob too large for a manifest");
		}

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) blob.size());

		blob.writeTo(bytes);
		return bytes.toByteArray();
	}

	private Run run(final RunInfo info) {
		return new Run(info, blobs, cache, config.getMaxBlockBytes());
	}

	private List<Run> runs(final Manifest manifest) {
		return manifest.runs().stream().map(this::run).toList();
	}

	Snapshot snapshot() {
		return snapshot;
	}

	Optional<BlobId> root() {
		return Optional.ofNullable(head.root());
	}

	long unflushedBytes() {
		return snapshot.active().bytes();
	}

	/* Runs the work under the writer lock and commits what it wrote, or nothing when it throws */
	<T, E extends Exception> T write(final Work<T, E> work) throws IOException, BlobStoreException, E {
		writer.lock();

		try {
			final Transaction transaction = new Transaction(snapshot);
			final T result = work.run(transaction);

			if (!transaction.batch().isEmpty()) {
				journal.append(transaction.sequence(), transaction.batch());
				snapshot.active().apply(transaction.sequence(), transaction.batch());
				snapshot = snapshot.at(transaction.sequence());
				dirtySince = Objects.requireNonNullElseGet(dirtySince, config.getClock()::instant);
			}

			return result;
		} finally {
			writer.unlock();
		}
	}

	/* Makes every write committed before the call part of the namespace the root names */
	void flush() throws IOException, BlobStoreException {
		maintainer.lock();

		try {
			persistFrozen();
			freeze();
			persistFrozen();
		} finally {
			maintainer.unlock();
		}
	}

	private void freeze() throws IOException {
		writer.lock();

		try {
			if (!snapshot.active().isEmpty()) {
				journal.freeze();
				snapshot = snapshot.freeze();
				dirtySince = null;
			}
		} finally {
			writer.unlock();
		}
	}

	/* Stores the frozen memtable left by a freeze, or by a flush that failed before its root moved */
	private void persistFrozen() throws IOException, BlobStoreException {
		final Snapshot current = snapshot;
		final Optional<Memtable> frozen = current.frozen();

		if (frozen.isPresent()) {
			final Cursor slots = Cursor.of(frozen.get().scan(FIRST_KEY, null, Long.MAX_VALUE));
			final Optional<Run> run = RunWriter.write(slots, frozen.get().count(), !current.runs().isEmpty(),
				directory, blobs, config).map(this::run);
			final List<Run> runs = Stream.concat(run.stream(), current.runs().stream()).toList();

			install(runs, List.of(), current.sequence(), latest -> latest.thaw(runs));
			journal.release();
		}
	}

	/* Merges a window of the newest runs into one, or every run when asked to or once there are more than the
	   configured count. The window takes the newest run and then each older one no larger than the runs before it
	   together, which keeps the number of runs about logarithmic in the size of the namespace while the newest,
	   smallest runs merge most often. A merge that reaches the oldest run drops tombstones, as nothing older is
	   left for them to hide. Answers whether it merged anything. */
	boolean compact(final boolean everything) throws IOException, BlobStoreException {
		maintainer.lock();

		try {
			final List<Run> runs = snapshot.runs();
			final int window = everything || runs.size() > config.getMaxRuns() ? runs.size() : window(runs);

			if (window < 2) {
				return false;
			}

			final List<Run> merged = runs.subList(0, window);
			final List<Run> older = runs.subList(window, runs.size());
			final List<Cursor> sources = merged.stream().map(run -> run.scan(FIRST_KEY, null)).toList();
			final Cursor slots = new MergeCursor(sources, true);
			final long entries = merged.stream().mapToLong(each -> each.info().entries()).sum();
			final Optional<Run> run = RunWriter.write(slots, entries, !older.isEmpty(), directory, blobs, config)
				.map(this::run);
			final List<Run> next = Stream.concat(run.stream(), older.stream()).toList();

			install(next, merged.stream().map(each -> each.info().id()).toList(), head.manifest().sequence(),
				latest -> latest.withRuns(next));

			return true;
		} finally {
			maintainer.unlock();
		}
	}

	private static int window(final List<Run> runs) {
		long newer = 0;
		int window = 0;

		while (window < runs.size() && (window == 0 || runs.get(window).info().size() <= newer)) {
			newer += runs.get(window++).info().size();
		}

		return window;
	}

	/* Deletes the superseded runs and manifests whose grace period is over. The manifest keeps listing them until
	   the next one is written, and a blob that is already gone counts as deleted. */
	void sweep() throws IOException, BlobStoreException {
		maintainer.lock();

		try {
			final Instant now = config.getClock().instant();

			for (Manifest.Pending pending : head.manifest().pending()) {
				if (!pending.due().isAfter(now) && !swept.contains(pending.blob())) {
					delete(blobs, pending.blob());
					swept.add(pending.blob());
				}
			}
		} finally {
			maintainer.unlock();
		}
	}

	static void delete(final BlobStore blobs, final BlobId blob) throws IOException, BlobStoreException {
		try {
			blobs.delete(blob);
		} catch (BlobNotFoundException gone) {
			/* Deleted before, by a sweep that stopped before its manifest recorded it */
		}
	}

	/* Flushes once the memtable is large or old enough, or a failed flush left work behind, then compacts and
	   sweeps */
	void maintain() throws IOException, BlobStoreException {
		final Instant since = dirtySince;
		final boolean old = Objects.nonNull(since) && !since.plus(config.getFlushInterval())
			.isAfter(config.getClock().instant());

		if (old || snapshot.frozen().isPresent() || snapshot.active().bytes() >= config.getFlushBytes()) {
			flush();
		}

		compact(false);
		sweep();
	}

	/* Stores the manifest of the runs and points the root at it, after which the superseded blobs and the manifest
	   the root named before wait out the grace period. Blobs already swept leave the pending list here. */
	private void install(final List<Run> runs, final List<BlobId> superseded, final long sequence,
		final UnaryOperator<Snapshot> change) throws IOException, BlobStoreException {

		final Head current = head;
		final Instant due = config.getClock().instant().plus(config.getGrace());
		final Stream<Manifest.Pending> released = Stream.concat(superseded.stream(),
			Stream.ofNullable(current.root())).map(blob -> new Manifest.Pending(blob, due));
		final Stream<Manifest.Pending> waiting = current.manifest().pending().stream()
			.filter(pending -> !swept.contains(pending.blob()));
		final Manifest next = new Manifest(sequence, runs.stream().map(Run::info).toList(),
			Stream.concat(waiting, released).toList());
		final BlobId root = blobs.store(new ByteArrayInputStream(next.encode()));

		LocalFiles.replace(directory.resolve(ROOT), root.value().getBytes(StandardCharsets.UTF_8));
		swept.clear();
		writer.lock();

		try {
			snapshot = change.apply(snapshot);
			head = new Head(next, root);
		} finally {
			writer.unlock();
		}
	}

	@Override
	public void close() throws IOException {
		journal.close();
	}
}
