/*
    Unigrid Hedgehog
    Copyright © 2021-2026 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
    the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.spork;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.ledger.ExclusiveLock;

/* A node holds its spork database in memory and writes it over the file as it saves, so a database installed beside
   a running node is lost at its next save. The node holds this lock in its data directory for as long as it runs,
   and an import refuses while anyone holds it. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkDatabaseLock {
	public static final String FILE = "spork.db.lock";

	private static final AtomicReference<ExclusiveLock> HELD = new AtomicReference<>();

	public static ExclusiveLock take(Path dataDirectory, ExclusiveLock.Locker locker) throws IOException {
		return ExclusiveLock.take(dataDirectory.resolve(FILE), locker, "the spork database in " + dataDirectory
			+ " is in use: a node runs on it, or an import");
	}

	/* Never let go of: the operating system does when the process ends, after the save of its shutdown */
	public static synchronized void holdWhileRunning(Path dataDirectory, ExclusiveLock.Locker locker)
		throws IOException {

		if (Objects.isNull(HELD.get())) {
			HELD.set(take(dataDirectory, locker));
		}
	}
}
