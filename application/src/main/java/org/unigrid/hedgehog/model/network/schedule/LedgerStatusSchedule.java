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
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.model.network.handler.RemoteAddress;
import org.unigrid.hedgehog.service.ledger.LedgerGateway;

@Data
@EqualsAndHashCode(callSuper = false)
public class LedgerStatusSchedule extends AbstractSchedule implements Schedulable {
	public static final int PERIOD_SECONDS = 10;

	public LedgerStatusSchedule() {
		super(PERIOD_SECONDS, TimeUnit.SECONDS, true);
	}

	/* The driver tells a stream where its chain stands, and does that for a stream once. Running again repairs the
	   case where no driver was attached yet when the stream was made; the driver sends its status by itself after. */
	@Override
	public Consumer<Channel> getConsumer() {
		return channel -> CDIUtil.resolveAndRun(LedgerGateway.class, gateway -> gateway.peerConnected(
			gateway.peerOf(channel, () -> RemoteAddress.of(channel))));
	}
}
