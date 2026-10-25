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
import io.netty.util.AttributeKey;
import java.util.Objects;
import java.util.Optional;

/**
* Why this node dialed a peer: one that an operator configured, as a seed or over REST, or one that it chose among those
* that peers told it of. The node marks a connection so as it opens it, and nothing that a peer sends marks one: anyone
* can connect to a node, while the peers that it dials are of its own choosing.
*/
public enum Dialed {
	CONFIGURED, CHOSEN;

	public static final AttributeKey<Dialed> KEY = AttributeKey.valueOf("DIALED");

	/* Why this node dialed the peer of a stream, or nothing for a peer that connected to it */
	public static Optional<Dialed> of(Channel stream) {
		return Optional.ofNullable(stream.parent()).map(connection -> connection.attr(KEY).get());
	}

	/* A node that an operator configures after it dialed it as one that it chose, as a peer told of it first */
	public static void configure(Channel stream) {
		Optional.ofNullable(stream.parent()).filter(connection -> Objects.nonNull(connection.attr(KEY).get()))
			.ifPresent(connection -> connection.attr(KEY).set(CONFIGURED));
	}
}
