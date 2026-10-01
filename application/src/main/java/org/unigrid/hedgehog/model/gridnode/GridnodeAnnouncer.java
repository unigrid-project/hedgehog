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

package org.unigrid.hedgehog.model.gridnode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.Topology;

@Slf4j
@ApplicationScoped
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class GridnodeAnnouncer {
	public static final Duration REFRESH_PERIOD = Duration.ofMinutes(5);

	@Inject private Topology topology;
	@Inject private GridnodeIdentity identity;
	private Supplier<String> address = GridnodeSetup::announcedAddress;

	public Optional<Gridnode> announce(Gridnode.Status status) {
		return announce(status, System.currentTimeMillis());
	}

	public Optional<Gridnode> announce(Gridnode.Status status, long nowMillis) {
		return identity.id().map(id -> {
			final long held = topology.findGridnode(id).map(Gridnode::getTimestamp).orElse(0L);
			final Gridnode entry = identity.sign(status, address.get(), nowMillis, held);

			if (!topology.offerGridnode(entry, nowMillis)) {
				log.atWarn().log("The own gridnode entry was refused; is the system clock far off?");
			}

			return entry;
		});
	}

	public void refresh() {
		refresh(System.currentTimeMillis());
	}

	public void refresh(long nowMillis) {
		identity.id().ifPresent(id -> {
			final Optional<Gridnode> own = topology.findGridnode(id);

			if (own.isEmpty() || nowMillis - own.get().getTimestamp() >= REFRESH_PERIOD.toMillis()) {
				announce(own.map(Gridnode::getStatus).orElse(Gridnode.Status.INACTIVE), nowMillis);
			}
		});
	}

	public void maintain() {
		maintain(System.currentTimeMillis());
	}

	/* Without a refresh the own entry would age out on a node nobody is connected to */
	public void maintain(long nowMillis) {
		refresh(nowMillis);
		topology.purgeGridnodes(nowMillis, identity.id());
	}
}
