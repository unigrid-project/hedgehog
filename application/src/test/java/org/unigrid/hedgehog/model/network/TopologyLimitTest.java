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

package org.unigrid.hedgehog.model.network;

import jakarta.inject.Inject;
import java.util.Optional;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.command.option.NetOptions;
import org.unigrid.hedgehog.command.option.RestOptions;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.jqwik.WeldSetup;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.cdi.ProtectedInterceptor;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.gridnode.GridnodeSignature;
import org.unigrid.hedgehog.server.TestServer;

/* Filling the set with correctly signed entries would take a key pair each, so the signature check is faked here and
   the class has the JVM to itself, because the fake outlives a single example */
@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class TopologyLimitTest extends BaseMockedWeldTest {
	private static final long NOW = 1_800_000_000_000L;

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();

		new MockUp<GridnodeSignature>() {
			@Mock public static boolean verifies(Gridnode gridnode) {
				return true;
			}
		};
	}

	private static Gridnode entry(String id, long timestamp) {
		return Gridnode.builder().id(id).status(Gridnode.Status.ACTIVE).hostName("10.0.0.1:1")
			.timestamp(timestamp).build();
	}

	@Example
	public void shouldRefuseNewIdsAtTheLimitKeepUpdatingHeldOnesAndTakeNewOnesAfterAPurge() {
		for (int i = 0; i < Topology.MAX_GRIDNODES; i++) {
			assertThat(topology.offerGridnode(entry("id-" + i, NOW), NOW), is(true));
		}

		assertThat(topology.offerGridnode(entry("one-too-many", NOW), NOW), is(false));
		assertThat(topology.findGridnode("one-too-many"), is(Optional.empty()));
		assertThat(topology.offerGridnode(entry("id-0", NOW + 1), NOW + 1), is(true));
		assertThat(topology.cloneGridnode().size(), is(Topology.MAX_GRIDNODES));

		final long later = NOW + GridnodeSignature.MAX_AGE.toMillis() + 1;

		topology.purgeGridnodes(later, Optional.empty());

		assertThat(topology.cloneGridnode().isEmpty(), is(true));
		assertThat(topology.offerGridnode(entry("late-comer", later), later), is(true));
	}
}
