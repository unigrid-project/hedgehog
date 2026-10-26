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
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.proxy.chain.Entry;

/* The peer is the stream a packet arrived on; whatever a method returns goes back on that stream and is never null */
public interface ChainService {
	/* The reply carries the request's id. Its encoder refuses a JOINED reply without a chain id or with more than
	   ChainCodecs.MAX_HEADS_PER_PACKET heads, and writes no chain id or heads for any other status */
	JoinReply join(Channel peer, JoinChain request);

	/* Refusals tell the writer to pause; entries that are simply dropped need none */
	List<EntryRefused> receive(Channel peer, ChainEntry entries);

	/* Asks the peer for the entries its heads show this node lacks; its encoder refuses more than
	   ChainCodecs.MAX_HEADS_PER_PACKET ranges, and the heads come from a stranger */
	Optional<FetchEntries> reconcile(Channel peer, ChainHeads heads);

	/* The retained entries of the requested ranges, oldest first, for the handler to split with ChainCodecs#batches;
	   an implementation caps the entries and bytes it serves per request, because a stranger may ask for a range
	   spanning [0, Long.MAX_VALUE] */
	List<Entry> fetch(Channel peer, FetchEntries request);

	/* A peer may name any admission, also one that is no refusal: only refusals of this node's own entries count */
	void refused(Channel peer, EntryRefused refusal);

	/* The packet is unsigned: it may only end what the peer itself holds on this stream */
	void leave(Channel peer, LeaveChain leave);
}
