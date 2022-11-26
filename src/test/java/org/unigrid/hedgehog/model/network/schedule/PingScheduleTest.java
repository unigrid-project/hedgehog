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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.ByteRange;
import net.jqwik.api.Property;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.TestServer;

public class PingScheduleTest extends BaseScheduleTest<PingSchedule, Ping, Void> {
	public PingScheduleTest() {
		super(300, TimeUnit.MILLISECONDS, PingSchedule.class);
	}

	final AtomicInteger shit = new AtomicInteger();

	@Property(tries = 3)
	public void shoulBeAbleToPingNetwork(@ForAll("provideTestServers") List<TestServer> servers,
		@ForAll @ByteRange(min = 3, max = 5) byte pingsPerServer) throws Exception {

		//final AtomicInteger invocations = new AtomicInteger();
		//int expectedInvocations = 0;

		setScheduleCallback(Optional.of(channel -> {
			System.out.println("its scheduling!!!");
		}));

		if (servers.size() > 0 ) {
			final String host = servers.get(0).getP2p().getHostName();
			final int port = servers.get(0).getP2p().getPort();
			final P2PClient client = new P2PClient(host, port);
		}

		Thread.sleep(3000);
	}
}
