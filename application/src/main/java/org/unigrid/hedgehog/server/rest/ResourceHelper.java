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

package org.unigrid.hedgehog.server.rest;

import jakarta.ws.rs.core.Response;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SerializationUtils;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;

@Slf4j
public class ResourceHelper {
	/**
	* Starts the next version of a spork from the stored one. The data of a proposal still awaiting its
	* co-signature is carried over, so several changes can be proposed in a row and co-signed once.
	*/
	public static <S extends GridSpork> S nextVersion(S stored, Supplier<S> newSupplier, PendingSporks pendingSporks) {
		final S spork = Objects.isNull(stored) ? newSupplier.get() : SerializationUtils.clone(stored);

		spork.archive();

		pendingSporks.proposalOf(spork.getType()).ifPresent(proposal -> {
			spork.setData(SerializationUtils.clone(proposal).getData());
		});

		return spork;
	}

	public static Response propose(GridSpork spork, String privateKey, GridSpork stored, PendingSporks pendingSporks,
		Topology topology) {

		try {
			spork.sign(privateKey);
		} catch (SigningException ex) {
			log.atWarn().log("Signing of spork refused: {}", ex.getMessage());
			return Response.status(Response.Status.UNAUTHORIZED).entity(ex).build();
		}

		if (!pendingSporks.offer(spork, stored)) {
			return Response.status(Response.Status.CONFLICT).build();
		}

		Topology.sendAll(PublishSpork.builder().gridSpork(spork).build(), topology, Optional.empty());
		return Response.accepted(PendingSporkInfo.of(spork)).build();
	}
}
