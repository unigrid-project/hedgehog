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
import io.netty.incubator.codec.quic.QuicChannel;
import java.util.Optional;
import javax.net.ssl.SSLEngine;
import org.unigrid.hedgehog.model.Network;

/* A node of the previous release reads neither a communication spork, which also throws it off the sporks behind
   one, nor chain traffic, and it offers only the older protocols. The protocol a QUIC connection agreed on thus
   tells what a peer can take: the proxy protocol, or the ledger protocol, which a node that runs the ledger offers
   ahead of it. It is known once the handshake is done, before the client opens a stream and before the server sets
   up one the client opened; until then a peer is taken for one of the previous release. */
public final class PeerProtocol {
	private PeerProtocol() {
		/* Static helpers only */
	}

	/* For a stream or the QUIC connection it belongs to */
	public static boolean speaksProxy(final Channel channel) {
		return connectionOf(channel).map(QuicChannel::sslEngine).map(SSLEngine::getApplicationProtocol)
			.filter(protocol -> Network.PROXY_PROTOCOL.equals(protocol) || Network.LEDGER_PROTOCOL.equals(protocol))
			.isPresent();
	}

	private static Optional<QuicChannel> connectionOf(final Channel channel) {
		return Optional.ofNullable(channel).map(stream -> stream instanceof QuicChannel ? stream : stream.parent())
			.filter(QuicChannel.class::isInstance).map(QuicChannel.class::cast);
	}
}
