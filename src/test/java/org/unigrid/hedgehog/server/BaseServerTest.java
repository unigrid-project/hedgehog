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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import lombok.Getter;
import me.alexpanov.net.FreePortFinder;
import mockit.Expectations;
import mockit.Mocked;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.Instances;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.cdi.CDIUtil;
import org.unigrid.hedgehog.server.p2p.P2PServer;
import org.unigrid.hedgehog.server.rest.RestServer;

@WeldSetup(ServerTest.TestServer.class)
public class BaseServerTest extends BaseMockedWeldTest {
	@Mocked
	protected NetOptions netOptions;

	@Mocked
	protected RestOptions restOptions;

	@Inject @Instances(5)
	protected List<TestServer> servers;

	@ApplicationScoped
	public static class TestServer {
		@Inject @Getter private P2PServer p2p;
		@Inject @Getter private RestServer rest;
	}

	@BeforeTry
	public void before() {
		for (TestServer s : servers) {
			new Expectations() {{
				int port = FreePortFinder.findFreeLocalPort();

				NetOptions.getHost(); result = "localhost";
				NetOptions.getPort(); result = port;
				RestOptions.getHost(); result = "localhost";
				RestOptions.getPort(); result = FreePortFinder.findFreeLocalPort(port + 1);
			}};

			CDIUtil.instantiate(s.getP2p());
			CDIUtil.instantiate(s.getRest());
		}
	}
}
