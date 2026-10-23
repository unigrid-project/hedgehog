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

package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.service.ledger.LedgerProtocol;

/* Whether the other end of a stream reads every spork type of this release: its connection settled on the protocol
   that this release offers ahead of what earlier releases offer, or on the ledger protocol, which no earlier release
   offers. The answer is read from the handshake once for each connection and kept on it, as LedgerProtocol does. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkProtocol {
	public static final AttributeKey<Boolean> NEGOTIATED_KEY = AttributeKey.valueOf("SPORK_PROTOCOL_NEGOTIATED");

	public static boolean negotiatedOn(final Channel stream) {
		return LedgerProtocol.negotiatedOn(stream)
			|| Optional.ofNullable(stream.parent()).map(SporkProtocol::settledOn).orElse(false);
	}

	/* A connection that has not said yet what it settled on is not remembered as not having settled on the protocol */
	private static boolean settledOn(final Channel connection) {
		final Attribute<Boolean> settled = connection.attr(NEGOTIATED_KEY);

		if (settled.get() == null) {
			LedgerProtocol.agreedOn(connection).ifPresent(protocol -> settled.set(Network.SPORK_PROTOCOL
				.equals(protocol)));
		}

		return Boolean.TRUE.equals(settled.get());
	}
}
