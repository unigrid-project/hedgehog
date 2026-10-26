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

package org.unigrid.hedgehog.service.chain;

import org.unigrid.hedgehog.proxy.chain.ChainId;

/* Whoever runs sessions on the chains of this node tells the host whether a retired chain still has any, and
   ends them when the grace of the chain is over */
public interface ChainDrain {
	/* Whether a session of this node still runs on the chain. Asked on the host's tick with no lock of the host
	   held; one that throws counts as a yes. */
	boolean holds(ChainId chainId);

	/* The grace of the chain is over and the host is about to drop it: end the sessions on it now, while the chain
	   can still carry what they post. Told on the host's tick with no lock of the host held, so it may post. A drain
	   that throws is told again at the next tick, and so are the others, so ending what has ended must do nothing. */
	void expired(ChainId chainId);
}
