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

package org.unigrid.hedgehog.ledger;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/* An exclusive lock on a file of its own, held from take() until close(). Another lock over the same file, in this
   process or in another, is refused with a FileLockedException, so two nodes never use the same files at once. */
public final class ExclusiveLock implements Closeable {
	/* How the lock is taken, which a test can replace, as the in-memory file system gives everyone a lock. Null means
	   that another process holds it; a lock held in this process is an OverlappingFileLockException. */
	@FunctionalInterface
	public interface Locker {
		FileLock tryLock(Path lockFile, FileChannel channel) throws IOException;
	}

	public static final Locker SYSTEM = (lockFile, channel) -> channel.tryLock();

	private final FileChannel channel;
	private final FileLock lock;

	private ExclusiveLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	/* The refusal names what is locked, which the caller knows and the lock file alone does not tell */
	public static ExclusiveLock take(Path lockFile, Locker locker, String refusal) throws IOException {
		Files.createDirectories(lockFile.toAbsolutePath().getParent());

		final FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

		try {
			return new ExclusiveLock(channel, acquire(lockFile, channel, locker, refusal));
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private static FileLock acquire(Path lockFile, FileChannel channel, Locker locker, String refusal)
		throws IOException {

		try {
			final FileLock acquired = locker.tryLock(lockFile, channel);

			if (acquired != null) {
				return acquired;
			}
		} catch (OverlappingFileLockException e) {
			throw new FileLockedException(refusal, e);
		}

		throw new FileLockedException(refusal);
	}

	@Override
	public void close() throws IOException {
		try (channel) {
			lock.release();
		}
	}
}
