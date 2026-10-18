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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.Getter;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Changed only under the lock of its store. The open file is the exception: readers use it outside the lock, which
   is safe because it is set before they get to it and stays put until the last of them has left. */
final class CacheEntry {
	@Getter private final String name;
	@Getter private final Path path;
	private final CompletableFuture<Long> loaded = new CompletableFuture<>();
	private Popularity popularity;
	@Getter private long size;
	@Getter private CacheFile file;
	private int readers;

	CacheEntry(final String name, final Path path, final Popularity popularity) {
		this.name = name;
		this.path = path;
		this.popularity = popularity;
	}

	void hit(final Instant now, final Duration halfLife) {
		popularity = popularity.hit(now, halfLife);
	}

	double score(final Instant now, final Duration halfLife) {
		return popularity.decayed(now, halfLife).hits() / Math.max(1, size);
	}

	/* Has its file in place, and nobody reading it */
	boolean isIdle() {
		return readers == 0 && loaded.isDone() && !loaded.isCompletedExceptionally();
	}

	void pin() throws IOException {
		if (readers == 0 && loaded.isDone()) {
			file = CacheFile.open(path);
		}

		readers++;
	}

	void unpin() throws IOException {
		if (--readers == 0 && file != null) {
			file.close();
			file = null;
		}
	}

	void place(final long placed) throws IOException {
		size = placed;

		if (readers > 0) {
			file = CacheFile.open(path);
		}

		loaded.complete(placed);
	}

	boolean fail(final Throwable cause) {
		return loaded.completeExceptionally(cause);
	}

	void abandon() {
		loaded.cancel(false);
	}

	/* Rethrows what made the load fail, and a CancellationException if it was abandoned */
	long await() throws IOException, BlobStoreException {
		try {
			return loaded.join();
		} catch (CompletionException ex) {
			if (ex.getCause() instanceof IOException cause) {
				throw cause;
			}

			if (ex.getCause() instanceof BlobStoreException cause) {
				throw cause;
			}

			throw ex;
		}
	}
}
