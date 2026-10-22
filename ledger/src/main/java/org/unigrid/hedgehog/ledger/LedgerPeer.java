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

package org.unigrid.hedgehog.ledger;

import java.util.concurrent.CompletableFuture;

/* One node on the other end of a connection. Two peers with the same id are one peer to the driver: the limits and
   the rule that nothing goes back to where it came from are by id, which for the network is the remote address. */
public interface LedgerPeer {
	String id();

	/* What the limits of the peer count under together with those of other peers, such as the IPv6 site whose holder
	   can have as many addresses as it likes, while each address is a peer of its own; the peer alone unless the
	   network knows better */
	default String quota() {
		return id();
	}

	/* The wire object is a Transaction, a Heartbeat, a Message or a Status; a send that cannot be made loses the
	   object, as the protocol is made to cope with. */
	void send(Object wire);

	CompletableFuture<BlockBatch> request(BlockRequest request);

	/* True for a peer that this node connected to, as against one that connected to it. A node chooses whom it dials
	   and not who dials it, so when claims are equal the ones it dialled are asked for blocks first. */
	default boolean dialled() {
		return false;
	}
}
