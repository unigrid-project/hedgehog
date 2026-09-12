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

package org.unigrid.hedgehog.model.producer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.unigrid.hedgehog.command.option.SnapshotOptions;
import org.unigrid.hedgehog.common.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.bootstrap.BootstrapSnapshot;

@ApplicationScoped
public class BootstrapSnapshotProducer {
	@Inject private ApplicationDirectory applicationDirectory;
	private AtomicReference<BootstrapSnapshot> snapshot = new AtomicReference<>(null);

	@Produces
	private BootstrapSnapshot produce() {
		if (Objects.isNull(snapshot.get())) {
			snapshot.set(new BootstrapSnapshot(applicationDirectory.getUserDataDir()
				.resolve(SnapshotOptions.SNAPSHOT_FILE)));
		}

		return snapshot.get();
	}
}
