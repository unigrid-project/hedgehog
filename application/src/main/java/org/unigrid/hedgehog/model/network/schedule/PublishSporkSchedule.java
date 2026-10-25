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

package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.Channel;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.handler.SporkRecipients;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.PendingSporks;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

@Data
@EqualsAndHashCode(callSuper = false)
public class PublishSporkSchedule extends AbstractSchedule implements Schedulable {
	public PublishSporkSchedule() {
		super(PublishSpork.DISTRIBUTION_FREQUENCY_MINUTES, TimeUnit.MINUTES, false);
	}

	@Override
	public Consumer<Channel> getConsumer() {
		return channel -> {
			CDIUtil.resolveAndRun(SporkDatabase.class, db -> {
				CDIUtil.resolveAndRun(PendingSporks.class, pendingSporks -> {
					writeAndFlush(channel, db, pendingSporks);
				});
			});
		};
	}

	/* A spork that is not set is not sent, nor one that does not count, which no node of the board as it is now
	   takes; and each only to a peer that reads it (SporkRecipients). The board keys go first, as every other spork is
	   judged by the keys they make active. */
	public static void writeAndFlush(Channel channel, SporkDatabase sporkDatabase, PendingSporks pendingSporks) {
		Stream.of(Type.BOARD_KEYS, Type.MINT_STORAGE, Type.MINT_SUPPLY, Type.VESTING_STORAGE, Type.STORAGE,
			Type.LEDGER_BLACKLIST, Type.LEDGER_FEE)
			.forEach(type -> SporkRecipients.write(channel, sporkDatabase.counting(type)));

		pendingSporks.list().forEach(spork -> SporkRecipients.write(channel, spork));
	}
}
