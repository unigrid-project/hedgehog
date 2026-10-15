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

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* The lock of a real file system, for the in-memory one that tests run on and that hands everyone a lock: a file that is
   locked here cannot be locked again until the lock is released, as on a real disk. A test that restarts a node over
   the files of another has to close the other first, which is what a process that starts again after another has
   gone does. The paths of different in-memory file systems differ, so tests do not meet in this set. */
final class StrictLocks {
	private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

	private StrictLocks() {
		/* Static helpers only */
	}

	static SignStateFile signStateOf(Path file) {
		return new SignStateFile(file, SignStateFile.MAX_VIEW, StrictLocks::tryLock);
	}

	static FileLock tryLock(Path lockFile, FileChannel channel) {
		if (!HELD.add(lockFile)) {
			throw new OverlappingFileLockException();
		}

		return new FileLock(channel, 0, Long.MAX_VALUE, false) {
			private boolean valid = true;

			@Override
			public boolean isValid() {
				return valid;
			}

			@Override
			public void release() {
				valid = false;
				HELD.remove(lockFile);
			}
		};
	}
}
