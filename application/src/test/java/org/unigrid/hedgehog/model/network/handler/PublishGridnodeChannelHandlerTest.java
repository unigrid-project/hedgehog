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

import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
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
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.server.TestServer;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.copyOf;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.signed;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class PublishGridnodeChannelHandlerTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;
	@Mocked private ChannelHandlerContext context;

	@Inject private Topology topology;

	private static final List<Packet> forwarded = new ArrayList<>();

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
		forwarded.clear();

		new MockUp<Topology>() {
			@Mock public static void sendAllExcept(Packet packet, Topology topology, Optional<Node> excluded) {
				forwarded.add(packet);
			}
		};
	}

	private void receive(Gridnode gridnode) throws Exception {
		new PublishGridnodeChannelHandler().typedChannelRead(context,
			PublishGridnode.builder().gridnode(gridnode).build()
		);
	}

	@Example
	public void shouldStoreAndForwardAValidEntryUnchanged() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1",
			System.currentTimeMillis()
		);

		receive(entry);

		assertThat(topology.findGridnode(entry.getId()).get().getSignature(), is(entry.getSignature()));
		assertThat(forwarded.size(), is(1));
		assertThat(((PublishGridnode) forwarded.get(0)).getGridnode().getSignature(), is(entry.getSignature()));
	}

	@Example
	public void shouldNeitherStoreNorForwardWhatIsInvalid() throws Exception {
		final long now = System.currentTimeMillis();
		final Signature key = new Signature();
		final Gridnode good = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", now);

		receive(copyOf(good, g -> g.setHostName("10.6.6.6:1")));
		receive(signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", now - 3_600_000));

		assertThat(topology.findGridnode(good.getId()), is(Optional.empty()));
		assertThat(forwarded.isEmpty(), is(true));
	}

	@Example
	public void shouldNotForwardWhatItAlreadyHolds() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1",
			System.currentTimeMillis()
		);

		receive(entry);
		receive(entry);

		assertThat(forwarded.size(), is(1));
	}
}
