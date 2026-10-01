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
import jakarta.inject.Inject;
import mockit.Mocked;
import mockit.Verifications;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.server.TestServer;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.signed;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class PublishGridnodeConsumerTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@Example
	public void shouldSendOnCreationAndEveryPeriod() {
		final PublishGridnodeSchedule schedule = new PublishGridnodeSchedule();

		assertThat(schedule.isExecuteOnCreation(), is(true));
		assertThat(schedule.getPeriod(), is(PublishGridnode.DISTRIBUTION_FREQUENCY_MINUTES));
	}

	@Property(tries = 1)
	public void shouldSendEveryFreshEntryAndNoExpiredOne(@Mocked Channel channel) throws Exception {
		final long now = System.currentTimeMillis();
		final int before = topology.cloneGridnode().size();

		topology.offerGridnode(signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", now));
		topology.offerGridnode(signed(new Signature(), Gridnode.Status.INACTIVE, "10.0.0.2:1", now));
		topology.addGridnode(Gridnode.builder().id("expired").hostName("10.0.0.3:1").build());

		new PublishGridnodeSchedule().getConsumer().accept(channel);

		new Verifications() {{
			channel.writeAndFlush(withInstanceOf(PublishGridnode.class)); times = before + 2;
		}};
	}
}
