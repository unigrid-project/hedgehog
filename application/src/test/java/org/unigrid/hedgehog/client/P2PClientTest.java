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

package org.unigrid.hedgehog.client;

import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.server.BaseServerTest;
import org.unigrid.hedgehog.server.TestServer;

public class P2PClientTest extends BaseServerTest {
	@SneakyThrows
	@Property(tries = 5)
	public void shouldRemoveThreadsAfterClose(@ForAll("provideTestServers") List<TestServer> servers) {
		final double originalNumberOfThreads = Thread.activeCount();

		for (TestServer server : servers) {
			final P2PClient client = new P2PClient(server.getP2p().getHostName(), server.getP2p().getPort());
			client.close();
		}

		await().until(() -> (double) Thread.activeCount(), is(closeTo(originalNumberOfThreads, 2.0)));
	}
}
