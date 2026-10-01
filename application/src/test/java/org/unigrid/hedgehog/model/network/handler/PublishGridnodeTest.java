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

import jakarta.inject.Inject;
import java.util.List;
import mockit.Mocked;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.ShrinkingMode;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeFixtures;
import org.unigrid.hedgehog.model.network.Connection;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.model.network.schedule.PublishGridnodeSchedule;
import org.unigrid.hedgehog.server.TestServer;

public class PublishGridnodeTest extends BaseHandlerTest<PublishGridnode, PublishGridnodeChannelHandler> {
	@Inject
	private Topology topology;

	public PublishGridnodeTest() {
		super(PublishGridnodeChannelHandler.class);
	}

	@Property(tries = 30, shrinking = ShrinkingMode.OFF)
	public void shouldStoreAnnouncementsFromTheNetwork(@ForAll("provideTestServers") List<TestServer> servers,
		@Mocked PublishGridnodeSchedule schedule) throws Exception {

		for (TestServer server : servers) {
			final String host = server.getP2p().getHostName();
			final int port = server.getP2p().getPort();
			final Connection connection = new P2PClient(host, port);
			final Gridnode entry = GridnodeFixtures.signed(new Signature(), Gridnode.Status.ACTIVE,
				host + ":" + port, System.currentTimeMillis()
			);

			connection.send(PublishGridnode.builder().gridnode(entry).build());

			await().until(() -> topology.findGridnode(entry.getId()).isPresent());
			assertThat(topology.findGridnode(entry.getId()).get().getSignature(), is(entry.getSignature()));
			connection.closeDirty();
		}
	}
}
