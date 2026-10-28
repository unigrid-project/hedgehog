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
import java.util.Set;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.session.ExitCandidate;
import org.unigrid.hedgehog.proxy.session.ExitSelector;

/* One chain a client node joined in one epoch: the feed it writes on, the members of the group as they were when
   it joined, the streams to the members it attached to, and how it chooses the exit of a session among them */
public record ChainAttachment(int group, Epoch epoch, FeedWriter writer, List<String> members,
	List<Channel> streams, ExitSelector exits) {

	public ChainId chainId() {
		return writer.chainId();
	}

	public WriterId writerId() {
		return writer.writer();
	}

	/* The exit of a session, chosen at random among the active members of the group that announce a sealing key */
	public Optional<ExitCandidate> exit(final Set<String> excluded) {
		return exits.exit(excluded);
	}
}
