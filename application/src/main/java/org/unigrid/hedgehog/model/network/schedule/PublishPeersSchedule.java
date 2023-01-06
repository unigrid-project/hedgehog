/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.collections4.BidiMap;
import org.apache.commons.collections4.bidimap.DualHashBidiMap;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishPeers;

@Data
@EqualsAndHashCode(callSuper = false)
public class PublishPeersSchedule extends AbstractSchedule implements Schedulable {
	public PublishPeersSchedule() {
		super(PublishPeers.DISTRIBUTION_FREQUENCY_MINUTES, TimeUnit.MINUTES, false);
	}

	@Override
	public Consumer<Channel> getConsumer() {
		return channel -> {
			final Instance<Topology> topology = CDI.current().select(Topology.class);

			if (topology.isResolvable()) {
				final BidiMap<Channel, Node> nodesToSend = new DualHashBidiMap(topology.get().getNodes());

				nodesToSend.remove(channel); /* We don't need to send the receiver to itself */
				channel.writeAndFlush(PublishPeers.builder().nodes(nodesToSend.values()));
			}
		};
	}
}
