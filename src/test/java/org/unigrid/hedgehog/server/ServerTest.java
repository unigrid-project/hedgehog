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

package org.unigrid.hedgehog.server;

import java.util.HashSet;
import java.util.Set;
import net.jqwik.api.Disabled;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.client.P2PClient;
import org.unigrid.hedgehog.model.network.packet.Ping;
import org.unigrid.hedgehog.server.p2p.P2PServer;

public class ServerTest extends BaseServerTest {
	@Example @Disabled
	public boolean shoulBeAbleTodStartMultipleIndependentServers() {
		final Set<Integer> ports = new HashSet<>();

		for (TestServer s : servers) {
			final int p = s.getP2p().getPort();

			if (ports.contains(p)) {
				return false;
			}

			ports.add(p);
		}

		return true;
	}

	@Example
	public void shoulBeAbleToDistributePeers() throws Exception {
		P2PServer from = servers.get(0).getP2p();
		P2PServer to = servers.get(1).getP2p();

		try {
			final P2PClient client = new P2PClient(to.getHostName(), to.getPort());
			client.send(Ping.builder().build()).sync();
			for (int i = 0; i < 5; i++) {
			Thread.sleep(500);
			}
			client.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
}
