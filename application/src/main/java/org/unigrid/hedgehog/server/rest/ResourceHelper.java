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
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.handler.SporkRecipients;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.PendingSporkInfo;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

@Slf4j
public class ResourceHelper {
	@SuppressWarnings("unchecked")
	public static <S extends GridSpork> S nextVersion(S stored, Supplier<S> newSupplier, PendingSporks pendingSporks) {
		final GridSpork.Type type = newSupplier.get().getType();

		return (S) GridSpork.nextVersion(type, stored, pendingSporks.proposalOf(type));
	}

	/**
	* Signs a spork and holds it as a proposal. The node that proposes it records the proposal in the lag tracker as
	* every node that receives it does, so that it too waits for the co-signature before it acts on the stored spork,
	* and does not rely on a copy coming back from the peers.
	*/
	public static Response propose(GridSpork spork, String privateKey, SporkDatabase sporkDatabase,
		PendingSporks pendingSporks, Topology topology, SporkLag lag) {

		try {
			spork.sign(privateKey);
		} catch (SigningException ex) {
			log.atWarn().log("Signing of spork refused: {}", ex.getMessage());
			return Response.status(Response.Status.UNAUTHORIZED).entity(ex).build();
		}

		if (!pendingSporks.offer(spork, sporkDatabase.get(spork.getType()))) {
			return Response.status(Response.Status.CONFLICT).build();
		}

		lag.record(spork, sporkDatabase, SporkLag.LOCAL);
		SporkRecipients.sendAll(spork, topology);
		return Response.accepted(PendingSporkInfo.of(spork)).build();
	}
}
