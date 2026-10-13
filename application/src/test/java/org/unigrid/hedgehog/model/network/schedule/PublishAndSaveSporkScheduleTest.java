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

package org.unigrid.hedgehog.model.network.schedule;

import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import org.hamcrest.Matcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.StorageSpork;
import org.unigrid.hedgehog.model.spork.VestingStorage;

public class PublishAndSaveSporkScheduleTest {
	private static List<GridSpork> published(EmbeddedChannel channel) {
		return Stream.<PublishSpork>generate(channel::readOutbound).takeWhile(Objects::nonNull)
			.map(PublishSpork::getGridSpork).collect(Collectors.toList());
	}

	private static Matcher<GridSpork> same(GridSpork spork) {
		return sameInstance(spork);
	}

	private static SporkDatabase.SporkDatabaseBuilder completeDatabase() {
		return SporkDatabase.builder().mintStorage(new MintStorage()).mintSupply(new MintSupply())
			.vestingStorage(new VestingStorage()).storageSpork(new StorageSpork())
			.communicationSpork(new CommunicationSpork());
	}

	/* Written on a stream of a QUIC connection that agreed on the protocols, the sporks are kept instead of sent */
	private static List<GridSpork> publishedOver(String[] serverProtocols, SporkDatabase sporkDatabase)
		throws InterruptedException {

		try (QuicLoopback loopback = QuicLoopback.between(serverProtocols, Network.getProtocols())) {
			final EmbeddedChannel channel = new EmbeddedChannel(loopback.clientEnd(),
				DefaultChannelId.newInstance(), true, false);

			PublishAndSaveSporkSchedule.writeAndFlush(channel, sporkDatabase);
			return published(channel);
		}
	}

	@Example
	public void publishesTheCommunicationSporkToANodeOfThisRelease() throws InterruptedException {
		final CommunicationSpork spork = new CommunicationSpork();
		final SporkDatabase sporkDatabase = SporkDatabase.builder().communicationSpork(spork).build();

		assertThat(publishedOver(Network.getProtocols(), sporkDatabase), hasItem(sameInstance(spork)));
	}

	@Example
	public void publishesEverySporkInOrderToANodeOfThisRelease() throws InterruptedException {
		final SporkDatabase sporkDatabase = completeDatabase().build();
		final List<GridSpork> published = publishedOver(Network.getProtocols(), sporkDatabase);

		assertThat(published, contains(same(sporkDatabase.getMintStorage()),
			same(sporkDatabase.getMintSupply()), same(sporkDatabase.getVestingStorage()),
			same(sporkDatabase.getStorageSpork()), same(sporkDatabase.getCommunicationSpork())));
	}

	@Example
	public void publishesNullForAMissingCommunicationSporkToANodeOfThisRelease() throws InterruptedException {
		final SporkDatabase sporkDatabase = completeDatabase().communicationSpork(null).build();
		final List<GridSpork> published = publishedOver(Network.getProtocols(), sporkDatabase);

		assertThat(published, contains(same(sporkDatabase.getMintStorage()),
			same(sporkDatabase.getMintSupply()), same(sporkDatabase.getVestingStorage()),
			same(sporkDatabase.getStorageSpork()), nullValue(GridSpork.class)));
	}

	@Example
	public void publishesEverySporkButTheCommunicationSporkToANodeOfThePreviousRelease() throws InterruptedException {
		final SporkDatabase sporkDatabase = completeDatabase().build();

		assertThat(publishedOver(PREVIOUS_RELEASE, sporkDatabase), contains(same(sporkDatabase.getMintStorage()),
			same(sporkDatabase.getMintSupply()), same(sporkDatabase.getVestingStorage()),
			same(sporkDatabase.getStorageSpork())));
	}

	@Example
	public void publishesNoCommunicationSporkWhileThePeerIsNotKnownToTakeIt() {
		final SporkDatabase sporkDatabase = completeDatabase().build();
		final EmbeddedChannel channel = new EmbeddedChannel();

		PublishAndSaveSporkSchedule.writeAndFlush(channel, sporkDatabase);
		assertThat(published(channel), contains(same(sporkDatabase.getMintStorage()),
			same(sporkDatabase.getMintSupply()), same(sporkDatabase.getVestingStorage()),
			same(sporkDatabase.getStorageSpork())));
	}
}
