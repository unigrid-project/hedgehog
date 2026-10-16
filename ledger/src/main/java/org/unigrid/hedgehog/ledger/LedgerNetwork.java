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

import java.util.List;

/* The peers that are connected right now, as a snapshot that the caller is free to walk */
@FunctionalInterface
public interface LedgerNetwork {
	List<LedgerPeer> peers();

	/* Closes the connections to the peer of that id: the driver asks it of a peer that it has penalised, and it is
	   asked once for each penalty. A network that cannot close a connection does nothing, and one that can does not
	   throw. */
	default void disconnect(String peerId) {
		/* Nothing is closed unless the network can */
	}
}
