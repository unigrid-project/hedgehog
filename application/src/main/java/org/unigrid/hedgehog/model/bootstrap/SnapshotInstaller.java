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

package org.unigrid.hedgehog.model.bootstrap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.unigrid.hedgehog.model.NodeStatus;

/*
   A node without a snapshot is still a working node, so a failed download is logged and reported on
   the status, and the node carries on running without one.
*/
@Slf4j
@ApplicationScoped
public class SnapshotInstaller {
	static final Duration FIRST_PAUSE = Duration.ofSeconds(10);
	static final Duration LONGEST_PAUSE = Duration.ofMinutes(10);

	@Inject private BootstrapSnapshot snapshot;
	@Inject private NodeStatus status;

	private Pause pause = Thread::sleep;

	/* Whether the last attempt failed, which the status tells until an attempt that does not */
	private volatile boolean failed;

	@FunctionalInterface
	interface Pause {
		void take(Duration length) throws InterruptedException;
	}

	/*
	   A download fails for reasons that pass, such as a network that is not up yet after a boot, so it is tried
	   again for as long as the node runs without a snapshot, after a pause that doubles up to the longest one. A
	   failure that cannot pass, such as a published hash that is wrong, is not told from one that can and is tried
	   again as well. An interrupt, which is how the node stops the install, ends it with no further attempt.
	*/
	public void installUntilInstalled(URL source) {
		Duration length = FIRST_PAUSE;

		for (installIfMissing(source); failed && isPausedFor(length); installIfMissing(source)) {
			length = ObjectUtils.min(length.multipliedBy(2), LONGEST_PAUSE);
		}
	}

	public void installIfMissing(URL source) {
		final boolean failedBefore = failed;

		failed = false;

		if (isInstalled()) {
			/* The snapshot came another way since a download failed, as `bootstrap fetch` brings it, or the node
			   learned of the board key that signed the one it refused */
			if (failedBefore) {
				status.running();
			}

			return;
		}

		status.downloading(null);

		try {
			SnapshotDownload.install(source, snapshot.getPath(), status::downloading);
			status.running();

		} catch (IOException ex) {
			log.atWarn().log("Could not install the legacy chain snapshot from {}: {}", source, ex.getMessage());
			failed = true;
			status.failed(Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName()));

		} catch (RuntimeException ex) {
			status.running();
			throw ex;
		}
	}

	/*
	   A file that the node refuses to open is no snapshot to it: one that is cut off, of an older format, or signed
	   by no board key that the node holds. The snapshot of the release is fetched over such a file, which stays
	   where it is until a download is verified. A snapshot that opens without a signature, as one that the operator
	   built, is kept. The file is looked for first, as a snapshot that was opened before is still answered for a
	   file that is gone.
	*/
	private boolean isInstalled() {
		if (!Files.exists(snapshot.getPath())) {
			return false;
		}

		if (snapshot.getReader().isPresent()) {
			return true;
		}

		log.atWarn().log("Replacing the legacy chain snapshot at {}, which this node does not use, with the one of"
			+ " its release: {}", snapshot.getPath(), snapshot.getRefusal().orElse("it does not open"));
		return false;
	}

	/* Whether the pause ran to its end, which it does not on a thread that is interrupted: the interrupt is kept */
	private boolean isPausedFor(Duration length) {
		if (Thread.currentThread().isInterrupted()) {
			return false;
		}

		try {
			log.atInfo().log("Trying the legacy chain snapshot again in {} seconds", length.toSeconds());
			pause.take(length);
			return true;

		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
