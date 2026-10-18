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
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.NodeStatus;

/*
   A node without a snapshot is still a working node, so a failed download is logged and reported on
   the status, and the node carries on running without one.
*/
@Slf4j
@ApplicationScoped
public class SnapshotInstaller {
	@Inject private BootstrapSnapshot snapshot;
	@Inject private NodeStatus status;

	public void installIfMissing(URL source) {
		if (Files.exists(snapshot.getPath())) {
			return;
		}

		status.downloading(null);

		try {
			SnapshotDownload.install(source, snapshot.getPath(), status::downloading);
			status.running();

		} catch (IOException ex) {
			log.atWarn().log("Could not install the legacy chain snapshot from {}: {}", source, ex.getMessage());
			status.failed(Objects.requireNonNullElse(ex.getMessage(), ex.getClass().getSimpleName()));

		} catch (RuntimeException ex) {
			status.running();
			throw ex;
		}
	}
}
