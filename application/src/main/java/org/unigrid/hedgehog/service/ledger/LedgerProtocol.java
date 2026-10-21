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

package org.unigrid.hedgehog.service.ledger;

import io.netty.channel.Channel;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.Optional;
import javax.net.ssl.SSLEngine;
import org.unigrid.hedgehog.model.Network;

/* The capability gate of the ledger. A node offers the ledger protocol, `Network.LEDGER_PROTOCOL`, first among the
   protocols of the handshake of a QUIC connection, and the other end settles on it only if it offers it too: a node of
   an earlier release does not, and settles on the protocol it has always offered first. A stream carries the ledger
   protocol only if the connection that it belongs to settled on it. Nothing of the ledger is sent over any other
   stream, which an earlier release would take for the start of a packet it does not know, and what comes in over one
   is not accepted. The answer is read from the handshake once for each connection and kept on it. */
public final class LedgerProtocol {
	public static final AttributeKey<Boolean> NEGOTIATED_KEY = AttributeKey.valueOf("LEDGER_PROTOCOL_NEGOTIATED");

	private LedgerProtocol() {
		/* Static helpers only */
	}

	public static boolean negotiatedOn(final Channel stream) {
		return Optional.ofNullable(stream.parent()).map(LedgerProtocol::settledOn).orElse(false);
	}

	/* A connection that has not said yet what it settled on is not remembered as not having settled on the ledger */
	private static boolean settledOn(final Channel connection) {
		final Attribute<Boolean> settled = connection.attr(NEGOTIATED_KEY);

		if (settled.get() == null) {
			agreedOn(connection).ifPresent(protocol -> settled.set(Network.LEDGER_PROTOCOL.equals(protocol)));
		}

		return Boolean.TRUE.equals(settled.get());
	}

	private static Optional<String> agreedOn(final Channel connection) {
		if (connection instanceof QuicChannel quic) {
			return Optional.ofNullable(quic.sslEngine()).map(SSLEngine::getApplicationProtocol)
				.filter(protocol -> !protocol.isEmpty());
		}

		return Optional.empty();
	}
}
