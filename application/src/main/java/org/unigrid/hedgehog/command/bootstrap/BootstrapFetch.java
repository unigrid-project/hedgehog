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

package org.unigrid.hedgehog.command.bootstrap;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.common.model.Version;
import org.unigrid.hedgehog.model.bootstrap.SnapshotDownload;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "fetch",
	description = "Download the published snapshot, verify it and install it."
)
public class BootstrapFetch implements Callable<Integer> {
	/* The signature, not the host, is what makes the file trustworthy. */
	private static final String RELEASES = "https://github.com/unigrid-project/hedgehog/releases/";
	private static final String ASSET = "bootstrap.dat.gz";
	private static final Pattern RELEASED_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+(-dev\\.\\d+)?");

	@Option(names = "--url", description = "Where to fetch the snapshot from (defaults to the one"
		+ " published with this release, or with the latest release for an unreleased build)."
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
			SnapshotDownload.install(url == null ? defaultUrl(Version.getVersionNumber()) : url, target);

		} catch (IOException ex) {
			System.err.println(ex.getMessage());
			return 2;
		}

		System.out.println("Installed " + target);
		return 0;
	}

	/* A released build carries the snapshot its release was published with; a snapshot build has no
	   release of its own. */
	public static URL defaultUrl(String version) throws MalformedURLException {
		final String path = RELEASED_VERSION.matcher(version).matches()
			? "download/v" + version + "/" : "latest/download/";

		return URI.create(RELEASES + path + ASSET).toURL();
	}
}
