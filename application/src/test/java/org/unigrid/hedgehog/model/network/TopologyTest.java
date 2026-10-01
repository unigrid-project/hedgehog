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

import io.netty.channel.Channel;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Optional;
import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
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
import org.unigrid.hedgehog.model.gridnode.GridnodeSignature;
import org.unigrid.hedgehog.model.network.packet.PublishGridnode;
import org.unigrid.hedgehog.server.TestServer;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.copyOf;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.signed;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class TopologyTest extends BaseMockedWeldTest {
	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	@Example
	public void shouldFindNodeByItsChangedAddress() {
		final InetSocketAddress original = new InetSocketAddress("127.0.100.1", 1000);
		final Node changed = Node.builder().address(new InetSocketAddress("127.0.100.2", 1000)).build();

		topology.addNode(Node.builder().address(original).build());
		topology.changeAddress(Node.builder().address(original).build(), changed.getAddress());

		assertThat(topology.containsNode(Node.builder().address(original).build()), is(false));
		assertThat(topology.containsNode(changed), is(true));
		assertThat(topology.removeNode(changed), is(true));
	}

	private static final long NOW = 1_800_000_000_000L;
	private static final long MAX_AGE = GridnodeSignature.MAX_AGE.toMillis();
	private static final long MAX_SKEW = GridnodeSignature.MAX_SKEW.toMillis();

	@Example
	public void shouldStoreAFreshSignedEntry() throws Exception {
		final Gridnode entry = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);

		assertThat(topology.offerGridnode(entry, NOW), is(true));
		assertThat(topology.findGridnode(entry.getId()).get().getSignature(), is(entry.getSignature()));
	}

	@Example
	public void shouldReplaceAnEntryWithANewerOne() throws Exception {
		final Signature key = new Signature();
		final Gridnode older = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode newer = signed(key, Gridnode.Status.INACTIVE, "10.0.0.1:1", NOW + 1);

		topology.offerGridnode(older, NOW);

		assertThat(topology.offerGridnode(newer, NOW + 1), is(true));
		assertThat(topology.findGridnode(older.getId()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	public void shouldNotLetARecordedOlderEntryRevertANewerOne() throws Exception {
		final Signature key = new Signature();
		final Gridnode active = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode inactive = signed(key, Gridnode.Status.INACTIVE, "10.0.0.1:1", NOW + 1000);

		topology.offerGridnode(active, NOW);
		topology.offerGridnode(inactive, NOW + 1000);

		assertThat(topology.offerGridnode(active, NOW + 1000), is(false));
		assertThat(topology.findGridnode(active.getId()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	public void shouldNotReplaceAnEntryByAnotherOneOfTheSameTime() throws Exception {
		final Signature key = new Signature();
		final Gridnode first = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode rival = signed(key, Gridnode.Status.ACTIVE, "10.9.9.9:1", NOW);

		topology.offerGridnode(first, NOW);

		assertThat(topology.offerGridnode(rival, NOW), is(false));
		assertThat(topology.findGridnode(first.getId()).get().getHostName(), is("10.0.0.1:1"));
	}

	@Example
	public void shouldRefuseExpiredFutureForgedAndUnsignedEntries() throws Exception {
		final Signature key = new Signature();
		final Gridnode good = signed(key, Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);

		assertThat(topology.offerGridnode(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW - MAX_AGE), NOW), is(false));
		assertThat(topology.offerGridnode(signed(key, Gridnode.Status.ACTIVE, "h:1", NOW + MAX_SKEW + 1), NOW),
			is(false));
		assertThat(topology.offerGridnode(copyOf(good, g -> g.setHostName("10.6.6.6:1")), NOW), is(false));
		assertThat(topology.offerGridnode(copyOf(good, g -> g.setSignature(null)), NOW), is(false));
		assertThat(topology.findGridnode(good.getId()), is(Optional.empty()));
	}

	@Example
	public void shouldPurgeExpiredEntriesButKeepTheOwnOne() throws Exception {
		final Gridnode own = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.1:1", NOW);
		final Gridnode stale = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.2:1", NOW);
		final Gridnode fresh = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.3:1", NOW + MAX_AGE);

		topology.offerGridnode(own, NOW);
		topology.offerGridnode(stale, NOW);
		topology.offerGridnode(fresh, NOW + MAX_AGE - 1);

		topology.purgeGridnodes(NOW + MAX_AGE, Optional.of(own.getId()));

		assertThat(topology.findGridnode(own.getId()).isPresent(), is(true));
		assertThat(topology.findGridnode(stale.getId()).isPresent(), is(false));
		assertThat(topology.findGridnode(fresh.getId()).isPresent(), is(true));
	}

	@Property(tries = 1)
	public void shouldSendToEveryConnectedNodeButTheExcludedOne(@Mocked Connection first, @Mocked Connection second,
		@Mocked Channel firstChannel, @Mocked Channel secondChannel) {

		final Node excluded = Node.builder().address(new InetSocketAddress("127.0.100.1", 1000))
			.connection(Optional.of(first)).build();
		final Node other = Node.builder().address(new InetSocketAddress("127.0.100.2", 1000))
			.connection(Optional.of(second)).build();
		final Packet packet = PublishGridnode.builder().build();

		new Expectations() {{
			first.getChannel(); result = firstChannel; minTimes = 0;
			second.getChannel(); result = secondChannel; minTimes = 0;
		}};

		topology.addNode(excluded);
		topology.addNode(other);
		Topology.sendAllExcept(packet, topology, Optional.of(excluded));

		new Verifications() {{
			firstChannel.writeAndFlush(packet); times = 0;
			secondChannel.writeAndFlush(packet); times = 1;
		}};
	}
}
