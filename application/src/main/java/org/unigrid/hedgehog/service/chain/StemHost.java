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

import io.netty.channel.Channel;
import java.util.List;
import java.util.Optional;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Offer;

/* What the stem of a host calls back into: the chain takes what the stem lets go of, by flood, the way it takes
   anything else, and the host floods and delivers it */
interface StemHost {
	/* Offers the entries to the chain and queues what it appended, as one step under the delivery lock of the
	   chain, and answers the offers in the order of the entries */
	List<Optional<Offer>> offerAndQueue(HostedChain hosted, List<Entry> entries, Channel origin, boolean tell);

	/* Hands the draining of the chain to the delivery executor */
	void handOver(HostedChain hosted);

	/* Passes on what waits for a member whose stream took no writes, now that it takes writes again */
	void retry(Channel stream);
}
