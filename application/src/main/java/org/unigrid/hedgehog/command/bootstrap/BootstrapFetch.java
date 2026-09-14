/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.command.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDownload;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "fetch",
	description = "Download the published snapshot, verify it and install it."
)
public class BootstrapFetch implements Callable<Integer> {
	/* Settled per release; the signature, not the host, is what makes the file trustworthy. */
	public static final String DEFAULT_URL =
		"https://github.com/unigrid-project/hedgehog/releases/latest/download/bootstrap.dat.gz";

	@Option(names = "--url", description = "Where to fetch the snapshot from"
		+ " (defaults to ${DEFAULT-VALUE}).", defaultValue = DEFAULT_URL
	)
	private URL url;

	@Option(names = "--force", description = "Replace an existing snapshot.")
	private boolean force;

	@Override
	public Integer call() throws IOException {
		final Path target = SnapshotOptions.getSnapshot();

		if (Files.exists(target) && !force) {
			System.err.println(target + " already exists, pass --force to replace it");
			return 1;
		}

		try {
			SnapshotDownload.install(url, target);

		} catch (IOException ex) {
			System.err.println(ex.getMessage());
			return 2;
		}

		System.out.println("Installed " + target);
		return 0;
	}
}
