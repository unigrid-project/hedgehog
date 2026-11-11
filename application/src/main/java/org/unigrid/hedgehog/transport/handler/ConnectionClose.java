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

package org.unigrid.hedgehog.transport.handler;

import io.netty.channel.Channel;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/* The close of a connection whose peer is gone, or is done with it, which is the same whatever told of it */
@Slf4j
public final class ConnectionClose {
	private ConnectionClose() {
		/* Static helpers only */
	}

	/* The QUIC connection that the stream runs on, as every stream of a peer that is gone is dead with it, and the
	   stream too: a connection that the peer closed before it went keeps its streams open after a close of its own,
	   until it idles out, and the topology judges a node by its stream */
	public static void of(Channel stream, String fault) {
		final Channel connection = Objects.requireNonNullElse(stream.parent(), stream);

		if (stream.isOpen()) {
			log.atInfo().addArgument(() -> Optional.<Object>ofNullable(connection
				.attr(ProtocolMismatchHandler.PEER_ADDRESS_KEY).get()).orElse(stream.remoteAddress()))
				.addArgument(fault).log("Closed the connection to {}, which {}");
			connection.close();
			stream.close();
		}
	}
}
