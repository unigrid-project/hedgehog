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

package org.unigrid.hedgehog.command.option;

import java.nio.file.Path;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SnapshotOptions {
	public static final String SNAPSHOT_FILE = "bootstrap.dat";

	@Option(names = { "-s", "--snapshot" }, scope = CommandLine.ScopeType.INHERIT,
		description = "Converted bootstrap file (defaults to " + SNAPSHOT_FILE + " in the data directory)."
	)
	private static Path snapshot;

	public static Path getSnapshot() {
		return snapshot != null ? snapshot : defaultSnapshot();
	}

	public static Path defaultSnapshot() {
		return ApplicationDirectory.create().getUserDataDir().resolve(SNAPSHOT_FILE);
	}
}
