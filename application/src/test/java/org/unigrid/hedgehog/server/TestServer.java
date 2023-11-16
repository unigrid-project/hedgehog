/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.Getter;
import me.alexpanov.net.FreePortFinder;
import mockit.Expectations;
import org.unigrid.hedgehog.command.option.GridnodeOptionsMockup;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;

@ApplicationScoped
public class TestServer {
	@Inject @Getter
	private P2PServer p2p;

	@Inject @Getter
	private RestServer rest;

	public static void mockProperties(TestServer server) {
		//new GridnodeOptionsMockup();
		mockProperties();

		CDIUtil.instantiate(server.getP2p());
		CDIUtil.instantiate(server.getRest());
	}

	public static void mockProperties() {

		new Expectations() {{
			int port = FreePortFinder.findFreeLocalPort();

			NetOptions.getHost(); result = "localhost";
			NetOptions.getPort(); result = port;
			RestOptions.getHost(); result = "localhost";
			RestOptions.getPort(); result = FreePortFinder.findFreeLocalPort(port + 1);
			Network.getSeeds(); result = new String[] { "127.0.200.1", "127.0.200.2", "127.0.200.3" };
		}};
	}
}
