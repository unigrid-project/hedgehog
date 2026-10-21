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
import io.netty.channel.ChannelFuture;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;

/* Streams that are one end of a connection that did, or did not, settle on the ledger protocol, as far as the gate
   reads it: the connection keeps the answer as an attribute once it has read it from the handshake. What is written
   to such a stream stays there to be read, as no codec is in its pipeline. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LedgerStreams {
	public static EmbeddedChannel stream(final boolean settledOnTheLedger) {
		final EmbeddedChannel connection = new EmbeddedChannel();

		connection.attr(LedgerProtocol.NEGOTIATED_KEY).set(settledOnTheLedger);
		return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false);
	}

	/* The sporks written to the stream so far, in order, taken off it */
	public static List<GridSpork.Type> sporkTypesWrittenTo(final EmbeddedChannel stream) {
		final List<GridSpork.Type> types = new ArrayList<>();
		Object written = stream.readOutbound();

		while (written != null) {
			if (written instanceof PublishSpork publish) {
				types.add(publish.getGridSpork().getType());
			}

			written = stream.readOutbound();
		}

		return types;
	}

	public static Connection connection(final Channel stream) {
		return new Connection() {
			@Override
			public Channel getChannel() {
				return stream;
			}

			@Override
			public ChannelFuture send(final Packet packet) {
				return stream.writeAndFlush(packet);
			}

			@Override
			public void close() {
				stream.close();
			}

			@Override
			public void closeDirty() {
				stream.close();
			}
		};
	}
}
