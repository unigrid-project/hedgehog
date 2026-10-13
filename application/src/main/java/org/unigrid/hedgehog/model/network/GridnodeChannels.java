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

package org.unigrid.hedgehog.model.network;

import io.netty.channel.Channel;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;

/* The streams this node holds to a gridnode: those of the connection of the topology node at the gridnode's
   address, the main stream and the one chain traffic has to itself */
@RequiredArgsConstructor
public class GridnodeChannels {
	private final Supplier<Set<Node>> nodes;

	public Optional<Channel> of(final Gridnode target) {
		return connectionOf(target).map(Connection::getChannel);
	}

	/* A closed chain stream is not handed out again: the peer has none for chain traffic until it connects anew */
	public Optional<Channel> chainOf(final Gridnode target) {
		return connectionOf(target).flatMap(Connection::getChainChannel).filter(Channel::isActive);
	}

	private Optional<Connection> connectionOf(final Gridnode target) {
		if (target.getHostName() == null) {
			return Optional.empty();
		}

		try {
			final Node wanted = Node.fromAddress(target.getHostName());

			return Optional.of(wanted).filter(GridnodeChannels::isResolved)
				.flatMap(node -> nodes.get().stream().filter(GridnodeChannels::isResolved)
					.filter(node::equals).findFirst())
				.flatMap(GridnodeChannels::connectionOf);
		} catch (URISyntaxException | IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	/* Node.equals compares resolved addresses and throws on a node without one; the topology set is shared with
	   other threads, so one malformed element must not make the whole lookup fail */
	private static boolean isResolved(final Node node) {
		return node != null && node.getAddress() != null && !node.getAddress().isUnresolved();
	}

	/* A lookup runs per packet: a node that never had its connection set means no stream, not a failure */
	private static Optional<Connection> connectionOf(final Node node) {
		final Optional<Connection> connection = node.getConnection();

		return connection == null ? Optional.empty() : connection;
	}
}
