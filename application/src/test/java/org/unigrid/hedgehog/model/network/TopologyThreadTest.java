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

package org.unigrid.hedgehog.model.network;

import jakarta.inject.Inject;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.awaitility.Awaitility.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.util.Reflection;
import org.unigrid.hedgehog.server.TestServer;

@WeldSetup({ TestServer.class, TopologyThread.class })
public class TopologyThreadTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;
	@Inject private TopologyThread topologyThread;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@Example
	public void shouldRepopulateWithSeedsIfEmpty() {
		topology.clear();
		topologyThread.start();
		await().until(() -> topology.cloneNodes().size(), is(Network.getSeeds().length));
	}

	@Example
	@SneakyThrows
	public void shouldCloneUniqueList() {
		final Set<Node> original = Reflection.getFieldValue(topology, "nodes");
		final Set<Node> cloned = topology.cloneNodes();

		topology.forEach(n -> {
			assertThat(cloned.contains(n), is(true));
		});

		assertThat(original, not(equalTo(cloned)));
	}
}
