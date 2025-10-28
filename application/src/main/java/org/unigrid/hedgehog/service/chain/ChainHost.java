/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import jakarta.enterprise.context.ApplicationScoped;
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

/* Hosts no chain yet, so the proxy stays off: every join is refused and chain traffic is dropped */
@ApplicationScoped
public class ChainHost implements ChainService {
	@Override
	public JoinReply join(final Channel peer, final JoinChain request) {
		return JoinReply.builder().requestId(request.getRequestId()).status(JoinReply.Status.DISABLED).build();
	}

	@Override
	public List<EntryRefused> receive(final Channel peer, final ChainEntry entries) {
		return List.of();
	}

	@Override
	public Optional<FetchEntries> reconcile(final Channel peer, final ChainHeads heads) {
		return Optional.empty();
	}

	@Override
	public List<Entry> fetch(final Channel peer, final FetchEntries request) {
		return List.of();
	}

	@Override
	public void refused(final Channel peer, final EntryRefused refusal) {
		/* No writer of this node is on a chain to pause */
	}

	@Override
	public void leave(final Channel peer, final LeaveChain leave) {
		/* No client is attached to detach */
	}
}
