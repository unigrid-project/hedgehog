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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;

/* Where a spork may be sent. A node of release 0.0.8 reads the two bytes of the type of a spork, and for a type it
   does not know it logs an error and leaves the rest of the frame unread, which it then reads on as more types:
   hundreds of error lines for one spork, a chance of taking garbage for a spork, and with an odd frame length the next
   frame is read out of step. The sporks of the ledger, the blacklist and the fee, are therefore sent only over a
   stream whose connection settled on the ledger protocol (LedgerProtocol), which a node of an earlier release never
   does. This holds for everything that writes a spork to a stream: the schedule and the new streams, the relay of a
   spork that came in, and the REST resources. A node that runs without --ledger does not offer the protocol either,
   so it neither sends nor relays these two sporks; it may still store one that a peer sent it. A spork that is not set
   is not sent at all. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkRecipients {
	private static final Set<Type> LEDGER_ONLY = Set.of(Type.LEDGER_BLACKLIST, Type.LEDGER_FEE);

	public static boolean needsLedgerProtocol(final Type type) {
		return LEDGER_ONLY.contains(type);
	}

	public static boolean mayReceive(final Channel stream, final GridSpork spork) {
		return Objects.nonNull(spork)
			&& (!needsLedgerProtocol(spork.getType()) || LedgerProtocol.negotiatedOn(stream));
	}

	public static void write(final Channel stream, final GridSpork spork) {
		if (mayReceive(stream, spork)) {
			stream.writeAndFlush(PublishSpork.builder().gridSpork(spork).build());
		}
	}

	public static void sendAll(final GridSpork spork, final Topology topology) {
		if (Objects.nonNull(spork)) {
			Topology.sendAll(PublishSpork.builder().gridSpork(spork).build(), topology,
				stream -> mayReceive(stream, spork), Optional.empty());
		}
	}
}
