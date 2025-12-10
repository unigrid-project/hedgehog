/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.producer;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;
import org.apache.commons.lang3.reflect.FieldUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.gridnode.Gridnode;
import org.unigrid.hedgehog.model.network.Node;
import org.unigrid.hedgehog.model.network.NodeConnections;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.MAX_UNSIGNED_INT;
import static org.unigrid.hedgehog.model.spork.CommunicationSporkTest.MAX_UNSIGNED_SHORT;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.proxy.group.ProxySettings;
import org.unigrid.hedgehog.service.chain.ChainTransport;
import org.unigrid.hedgehog.service.chain.ProxySettingsSource;

public class ChainProducerTest {
	private static final Map<String, ObjIntConsumer<SporkData>> SHORT_COUNTS = Map.of(
		"groupSize", SporkData::setGroupSize,
		"chainsPerGroup", SporkData::setChainsPerGroup,
		"clientsPerChain", SporkData::setClientsPerChain,
		"retentionSeconds", SporkData::setRetentionSeconds,
		"sessionIdleSeconds", SporkData::setSessionIdleSeconds
	);

	@SneakyThrows
	private static ChainProducer producerOver(final SporkDatabase database) {
		final ChainProducer producer = new ChainProducer();

		FieldUtils.writeField(producer, "sporkDatabase", database, true);
		return producer;
	}

	@SneakyThrows
	private static ChainProducer producerOver(final Topology topology) {
		final ChainProducer producer = new ChainProducer();

		FieldUtils.writeField(producer, "topology", topology, true);
		return producer;
	}

	/* Answers with copies, as the topology does, so that a change after the call stays out of the answer */
	private static Topology topologyOf(final Set<Node> nodes, final Set<Gridnode> gridnodes) {
		return new Topology() {
			@Override
			public Set<Node> cloneNodes() {
				return new HashSet<>(nodes);
			}

			@Override
			public Set<Gridnode> cloneGridnode() {
				return new HashSet<>(gridnodes);
			}
		};
	}

	private static Optional<ProxySettings> settingsOf(final SporkDatabase database) {
		return producerOver(database).proxySettings();
	}

	private static CommunicationSpork sporkOf(final SporkData data) {
		final CommunicationSpork spork = new CommunicationSpork();

		spork.setData(data);
		return spork;
	}

	private static SporkDatabase databaseWith(final SporkData data) {
		return SporkDatabase.builder().communicationSpork(sporkOf(data)).build();
	}

	private static Optional<ProxySettings> settingsAfter(final Consumer<SporkData> change) {
		final SporkData data = new SporkData();

		change.accept(data);
		return settingsOf(databaseWith(data));
	}

	private static SporkData dataOf(final ProxySettings settings) {
		final SporkData data = new SporkData();

		data.setGroupSize(settings.groupSize());
		data.setReorganizePeriodSeconds(settings.reorganizePeriodSeconds());
		data.setChainsPerGroup(settings.chainsPerGroup());
		data.setClientsPerChain(settings.clientsPerChain());
		data.setChainCapacityBytes(settings.chainCapacityBytes());
		data.setRetentionSeconds(settings.retentionSeconds());
		data.setMaxEntryBytes(settings.maxEntryBytes());
		data.setSessionIdleSeconds(settings.sessionIdleSeconds());
		data.setCommunicationFlags(settings.flags());
		return data;
	}

	private static void assertMapsTo(final ProxySettings expected) {
		assertThat(settingsOf(databaseWith(dataOf(expected))), equalTo(Optional.of(expected)));
	}

	private static void assertOff(final String reason, final Consumer<SporkData> change) {
		assertThat(reason, settingsAfter(change), equalTo(Optional.empty()));
	}

	@Provide
	public Arbitrary<SporkData> sporkData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds();
	}

	@Provide
	public Arbitrary<SporkData> validSporkData() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds().filter(CommunicationSporkTest::accepts);
	}

	@Example
	public void turnsTheProxyOffWithoutACommunicationSpork() {
		assertThat(settingsOf(SporkDatabase.builder().build()), equalTo(Optional.empty()));
	}

	@Example
	public void turnsTheProxyOffForASporkWithoutData() {
		assertThat(settingsOf(databaseWith(null)), equalTo(Optional.empty()));
	}

	@Example
	public void turnsTheProxyOffForDataThatRefusesItself() {
		final SporkData refusing = new SporkData() {
			@Override
			public void validate() {
				throw new IllegalArgumentException("refused");
			}
		};

		assertThat(settingsOf(databaseWith(refusing)), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheDefaultSporkAsTheDefaultSettings() {
		assertThat(settingsOf(databaseWith(new SporkData())), equalTo(Optional.of(ProxySettings.DEFAULTS)));
	}

	@Example
	public void readsANewCommunicationSporkAsTheDefaultSettings() {
		final SporkDatabase database = SporkDatabase.builder().communicationSpork(new CommunicationSpork()).build();

		assertThat(settingsOf(database), equalTo(Optional.of(ProxySettings.DEFAULTS)));
	}

	@Example
	public void asksTheDatabaseAgainWheneverTheSourceIsAsked() {
		final SporkDatabase database = SporkDatabase.builder().build();
		final ProxySettingsSource source = producerOver(database).proxySettingsSource();
		final SporkData invalid = new SporkData();
		final SporkData changed = new SporkData();

		invalid.setGroupSize(0);
		changed.setGroupSize(3);
		assertThat(source.current(), equalTo(Optional.empty()));

		database.setCommunicationSpork(sporkOf(new SporkData()));
		assertThat(source.current(), equalTo(Optional.of(ProxySettings.DEFAULTS)));

		database.setCommunicationSpork(sporkOf(invalid));
		assertThat(source.current(), equalTo(Optional.empty()));

		database.setCommunicationSpork(sporkOf(changed));
		assertThat(source.current().orElseThrow().groupSize(), is(3));
	}

	@Example
	public void mapsEachFieldToItsOwnSetting() {
		assertMapsTo(new ProxySettings(3, 7200, 5, 7, 5_000_000, 11, 2048, 13, 2));
	}

	@Example
	public void mapsTheSmallestValidSpork() {
		assertMapsTo(new ProxySettings(1, 60, 1, 1, 1024, 1, 1024, 1, 0));
	}

	@Example
	public void mapsTheLargestValidSpork() {
		assertMapsTo(new ProxySettings(MAX_UNSIGNED_SHORT, MAX_UNSIGNED_INT, MAX_UNSIGNED_SHORT, MAX_UNSIGNED_SHORT,
			MAX_UNSIGNED_INT, MAX_UNSIGNED_SHORT, 1_048_576, MAX_UNSIGNED_SHORT, 3));
	}

	@Example
	public void mapsAChainThatHoldsExactlyOneEntry() {
		assertMapsTo(new ProxySettings(8, 3600, 4, 16, 1024, 30, 1024, 300, 3));
		assertMapsTo(new ProxySettings(8, 3600, 4, 16, 1_048_576, 30, 1_048_576, 300, 3));
	}

	@Example
	public void turnsTheProxyOffForACountBeyondItsBounds() {
		SHORT_COUNTS.forEach((name, setter) -> {
			assertOff(name + " at 0", data -> setter.accept(data, 0));
			assertOff(name + " at 65536", data -> setter.accept(data, MAX_UNSIGNED_SHORT + 1));
		});
	}

	@Example
	public void turnsTheProxyOffForAPeriodOrEntrySizeBeyondItsBounds() {
		assertOff("reorganizePeriodSeconds at 59", data -> data.setReorganizePeriodSeconds(59));
		assertOff("reorganizePeriodSeconds at 4294967296",
			data -> data.setReorganizePeriodSeconds(MAX_UNSIGNED_INT + 1));
		assertOff("maxEntryBytes at 1023", data -> data.setMaxEntryBytes(1023));
		assertOff("maxEntryBytes at 1048577", data -> data.setMaxEntryBytes(1_048_577));
	}

	@Example
	public void turnsTheProxyOffForAChainThatCannotHoldAnEntryOrIsTooLarge() {
		assertOff("capacity below the smallest entry size", data -> {
			data.setMaxEntryBytes(1024);
			data.setChainCapacityBytes(1023);
		});

		assertOff("capacity below the largest entry size", data -> {
			data.setMaxEntryBytes(1_048_576);
			data.setChainCapacityBytes(1_048_575);
		});

		assertOff("capacity at 4294967296", data -> data.setChainCapacityBytes(MAX_UNSIGNED_INT + 1));
	}

	@Example
	public void turnsTheProxyOffForFlagsBeyondTheKnownOnes() {
		assertOff("flags at 4", data -> data.setCommunicationFlags(4));
		assertOff("flags at -1", data -> data.setCommunicationFlags(-1));
	}

	@Property
	public void turnsTheProxyOnOnlyForAValidSpork(@ForAll("sporkData") SporkData data) {
		final boolean valid = CommunicationSporkTest.accepts(data);

		Statistics.collect(valid);
		assertThat(settingsOf(databaseWith(data)).isPresent(), is(valid));

		Statistics.coverage(coverage -> {
			coverage.check(true).count(count -> count > 0);
			coverage.check(false).count(count -> count > 0);
		});
	}

	@Property
	public void mapsEveryFieldOfAValidSpork(@ForAll("validSporkData") SporkData data) {
		final ProxySettings settings = settingsOf(databaseWith(data)).orElseThrow();

		assertThat(settings.groupSize(), is(data.getGroupSize()));
		assertThat(settings.reorganizePeriodSeconds(), is(data.getReorganizePeriodSeconds()));
		assertThat(settings.chainsPerGroup(), is(data.getChainsPerGroup()));
		assertThat(settings.clientsPerChain(), is(data.getClientsPerChain()));
		assertThat(settings.chainCapacityBytes(), is(data.getChainCapacityBytes()));
		assertThat(settings.retentionSeconds(), is(data.getRetentionSeconds()));
		assertThat(settings.maxEntryBytes(), is(data.getMaxEntryBytes()));
		assertThat(settings.sessionIdleSeconds(), is(data.getSessionIdleSeconds()));
		assertThat(settings.flags(), is(data.getCommunicationFlags()));
	}

	@Example
	public void findsTheStreamToAGridnodeInTheTopologyItWasGiven() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final Topology topology = topologyOf(Set.of(NodeConnections.connected("10.0.0.1:52883", channel)),
			Set.of(Gridnode.builder().id("alpha").hostName("10.0.0.1:52883").build()));
		final ChainTransport transport = producerOver(topology).chainTransport();

		assertThat(transport.streamTo("alpha"), equalTo(Optional.of(channel)));
		assertThat(transport.streamTo("bravo"), equalTo(Optional.empty()));
	}

	@Example
	public void readsTheTopologyAgainForEveryStream() {
		final EmbeddedChannel channel = new EmbeddedChannel();
		final Set<Node> nodes = new HashSet<>();
		final ChainTransport transport = producerOver(topologyOf(nodes,
			Set.of(Gridnode.builder().id("alpha").hostName("10.0.0.1:52883").build()))).chainTransport();

		assertThat(transport.streamTo("alpha"), equalTo(Optional.empty()));

		nodes.add(NodeConnections.connected("10.0.0.1:52883", channel));
		assertThat(transport.streamTo("alpha"), equalTo(Optional.of(channel)));
	}
}
