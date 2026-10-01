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

package org.unigrid.hedgehog.model.gridnode;

import jakarta.inject.Inject;
import java.util.Optional;
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
import org.unigrid.hedgehog.model.network.ChannelMap;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.server.TestServer;
import static org.unigrid.hedgehog.model.gridnode.GridnodeFixtures.signed;

@WeldSetup(value = { Topology.class, ChannelMap.class, ProtectedInterceptor.class }, scan = false)
public class GridnodeAnnouncerTest extends BaseMockedWeldTest {
	private static final long NOW = 1_800_000_000_000L;
	private static final long PERIOD = GridnodeAnnouncer.REFRESH_PERIOD.toMillis();
	private static final String ADDRESS = "127.0.0.1:5000";

	@Mocked private Network network;
	@Mocked private NetOptions netOptions;
	@Mocked private RestOptions restOptions;

	@Inject private Topology topology;

	@BeforeTry
	public void before() {
		TestServer.mockProperties();
	}

	private GridnodeAnnouncer announcerOf(Signature key) {
		return new GridnodeAnnouncer(topology, GridnodeIdentity.of(key), () -> ADDRESS);
	}

	@Example
	public void shouldAnnounceTheOwnEntryWithTheGivenStatus() throws Exception {
		final Signature key = new Signature();
		final Optional<Gridnode> entry = announcerOf(key).announce(Gridnode.Status.ACTIVE, NOW);

		assertThat(entry.get().getHostName(), is(ADDRESS));
		assertThat(topology.findGridnode(key.getPublicKey()).get().getStatus(), is(Gridnode.Status.ACTIVE));
	}

	@Example
	public void shouldAnnounceNothingWithoutAKey() {
		final GridnodeAnnouncer announcer = new GridnodeAnnouncer(topology, GridnodeIdentity.none(), () -> ADDRESS);
		final int before = topology.cloneGridnode().size();

		assertThat(announcer.announce(Gridnode.Status.ACTIVE, NOW), is(Optional.empty()));

		announcer.maintain(NOW);
		assertThat(topology.cloneGridnode().size(), is(before));
	}

	@Example
	public void shouldStartInactiveWhenNothingIsHeld() throws Exception {
		final Signature key = new Signature();

		announcerOf(key).refresh(NOW);

		assertThat(topology.findGridnode(key.getPublicKey()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	public void shouldResignWithTheSameStatusOnlyWhenDue() throws Exception {
		final Signature key = new Signature();
		final GridnodeAnnouncer announcer = announcerOf(key);

		announcer.announce(Gridnode.Status.ACTIVE, NOW);
		announcer.refresh(NOW + PERIOD - 1);
		assertThat(topology.findGridnode(key.getPublicKey()).get().getTimestamp(), is(NOW));

		announcer.refresh(NOW + PERIOD);
		assertThat(topology.findGridnode(key.getPublicKey()).get().getTimestamp(), is(NOW + PERIOD));
		assertThat(topology.findGridnode(key.getPublicKey()).get().getStatus(), is(Gridnode.Status.ACTIVE));
	}

	@Example
	public void shouldResumeTheEntryPeersStillHold() throws Exception {
		final Signature key = new Signature();
		final Gridnode heldByPeers = signed(key, Gridnode.Status.ACTIVE, ADDRESS, NOW - PERIOD);

		topology.offerGridnode(heldByPeers, NOW);
		announcerOf(key).refresh(NOW);

		assertThat(topology.findGridnode(key.getPublicKey()).get().getStatus(), is(Gridnode.Status.ACTIVE));
		assertThat(topology.findGridnode(key.getPublicKey()).get().getTimestamp(), is(NOW));
	}

	@Example
	public void shouldSignNewerThanPeersHoldEvenWithAClockBehindThem() throws Exception {
		final Signature key = new Signature();
		final long ahead = NOW + 5 * 60 * 1000;

		topology.offerGridnode(signed(key, Gridnode.Status.ACTIVE, ADDRESS, ahead), NOW);

		final Gridnode entry = announcerOf(key).announce(Gridnode.Status.INACTIVE, NOW).get();

		assertThat(entry.getTimestamp(), is(ahead + 1));
		assertThat(topology.findGridnode(key.getPublicKey()).get().getStatus(), is(Gridnode.Status.INACTIVE));
	}

	@Example
	public void shouldKeepTheOwnEntryWhileOthersExpire() throws Exception {
		final Signature key = new Signature();
		final Gridnode stranger = signed(new Signature(), Gridnode.Status.ACTIVE, "10.0.0.9:1", NOW);
		final GridnodeAnnouncer announcer = announcerOf(key);

		announcer.announce(Gridnode.Status.ACTIVE, NOW);
		topology.offerGridnode(stranger, NOW);

		announcer.maintain(NOW + GridnodeSignature.MAX_AGE.toMillis() - 1);
		assertThat(topology.findGridnode(stranger.getId()).isPresent(), is(true));

		announcer.maintain(NOW + GridnodeSignature.MAX_AGE.toMillis() + PERIOD);
		assertThat(topology.findGridnode(stranger.getId()).isPresent(), is(false));
		assertThat(topology.findGridnode(key.getPublicKey()).isPresent(), is(true));
	}
}
