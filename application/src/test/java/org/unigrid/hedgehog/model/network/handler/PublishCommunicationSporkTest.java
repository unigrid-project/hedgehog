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

package org.unigrid.hedgehog.model.network.handler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.Network;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.QuicLoopback;
import static org.unigrid.hedgehog.model.network.QuicLoopback.PREVIOUS_RELEASE;
import org.unigrid.hedgehog.model.network.Topology;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.packet.PublishSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork;
import org.unigrid.hedgehog.model.spork.CommunicationSpork.SporkData;
import org.unigrid.hedgehog.model.spork.CommunicationSporkTest;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.spork.MintSupply;
import org.unigrid.hedgehog.model.spork.SporkDatabase;

public class PublishCommunicationSporkTest extends BaseMockedWeldTest {
	private static final Instant STORED_AT = Instant.parse("2025-06-01T12:00:00Z");
	private static final long ONE_SECOND_IN_NANOS = 1_000_000_000L;
	private static boolean everySignatureTrusted;

	@AllArgsConstructor
	enum Accepted {
		MAX_ENTRY_BYTES_AT_LOWER_LIMIT(data -> data.setMaxEntryBytes(1024)),
		MAX_ENTRY_BYTES_AT_UPPER_LIMIT(data -> data.setMaxEntryBytes(1_048_576)),
		REORGANIZE_PERIOD_AT_LOWER_LIMIT(data -> data.setReorganizePeriodSeconds(60)),
		CHAIN_CAPACITY_HOLDING_ONE_ENTRY(data -> data.setChainCapacityBytes(data.getMaxEntryBytes())),
		NO_FLAGS(data -> data.setCommunicationFlags(0));

		private final Consumer<SporkData> change;
	}

	@AllArgsConstructor
	enum Refused {
		MAX_ENTRY_BYTES_BELOW_LOWER_LIMIT(data -> data.setMaxEntryBytes(1023)),
		MAX_ENTRY_BYTES_ABOVE_UPPER_LIMIT(data -> data.setMaxEntryBytes(1_048_577)),
		REORGANIZE_PERIOD_OF_ZERO(data -> data.setReorganizePeriodSeconds(0)),
		REORGANIZE_PERIOD_BELOW_LOWER_LIMIT(data -> data.setReorganizePeriodSeconds(59)),
		GROUP_SIZE_OF_ZERO(data -> data.setGroupSize(0)),
		CHAIN_CAPACITY_BELOW_ONE_ENTRY(data -> data.setChainCapacityBytes(data.getMaxEntryBytes() - 1)),
		UNKNOWN_FLAG(data -> data.setCommunicationFlags(0x04));

		private final Consumer<SporkData> change;
	}

	@Inject
	private SporkDatabase sporkDatabase;

	private final List<Packet> relayed = new ArrayList<>();
	private final List<Predicate<Channel>> receivers = new ArrayList<>();
	private Signature trustedSigner;

	/* A fake outlives its property, so a property opts in to unchecked signatures through this flag */
	@BeforeContainer
	private static void checkSignaturesUnlessTrusted() {
		new MockUp<GridSpork>() {
			@Mock public boolean isValidSignature(Invocation invocation) {
				return everySignatureTrusted || invocation.<Boolean>proceed();
			}
		};
	}

	@BeforeProperty
	@SneakyThrows
	private void trustASignerAndCollectRelayedPackets() {
		trustedSigner = new Signature();
		everySignatureTrusted = false;

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				return new String[] { trustedSigner.getPublicKey() };
			}
		};

		new MockUp<Topology>() {
			@Mock public void sendAll(Packet packet, Topology topology, Predicate<Channel> to,
				Optional<?> consumer) {


				relayed.add(packet);
				receivers.add(to);
			}
		};
	}

	@BeforeTry
	private void startFromAnEmptyDatabase() {
		sporkDatabase.setCommunicationSpork(null);
		sporkDatabase.setMintSupply(null);
		relayed.clear();
		receivers.clear();
	}

	/* The module descriptor reads logback-classic only, so the in-memory appender of logback-core is out of reach */
	private static void readLogbackCore() {
		PublishCommunicationSporkTest.class.getModule()
			.addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());
	}

	private List<ILoggingEvent> warningsWhilePublishing(GridSpork spork) {
		readLogbackCore();

		final Logger logger = (Logger) LoggerFactory.getLogger(PublishSporkChannelHandler.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			publish(spork);
		} finally {
			logger.detachAppender(appender);
		}

		return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}

	@SneakyThrows
	private Packet publish(GridSpork spork) {
		final PublishSpork packet = PublishSpork.builder().gridSpork(spork).build();

		new PublishSporkChannelHandler().typedChannelRead(null, packet);
		return packet;
	}

	private static CommunicationSpork spork(Instant timeStamp) {
		final CommunicationSpork spork = new CommunicationSpork();

		spork.setTimeStamp(timeStamp);
		return spork;
	}

	@SneakyThrows
	private CommunicationSpork signed(CommunicationSpork spork) {
		spork.sign(trustedSigner.getPrivateKey());
		return spork;
	}

	private CommunicationSpork signed(Instant timeStamp, Consumer<SporkData> change) {
		final CommunicationSpork spork = spork(timeStamp);

		change.accept(spork.getData());
		return signed(spork);
	}

	private CommunicationSpork storeAnOlderSpork() {
		final CommunicationSpork stored = spork(STORED_AT);

		sporkDatabase.setCommunicationSpork(stored);
		return stored;
	}

	private void assertNothingChanged(CommunicationSpork stored) {
		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(stored));
		assertThat(relayed, is(empty()));
	}

	private void assertStoredAndRelayed(CommunicationSpork spork, Packet packet) {
		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(spork));
		assertThat(relayed, contains(sameInstance(packet)));
	}

	@Provide
	public Arbitrary<Long> notNewerNanos() {
		return Arbitraries.of(-ONE_SECOND_IN_NANOS, -1L, 0L);
	}

	@Provide
	public Arbitrary<Long> newerNanos() {
		return Arbitraries.of(1L, ONE_SECOND_IN_NANOS);
	}

	@Provide
	public Arbitrary<SporkData> sporkDataAcrossTheBounds() {
		return CommunicationSporkTest.sporkDataAcrossTheBounds();
	}

	@Example
	public void storesACommunicationSporkIntoAnEmptyDatabase() {
		everySignatureTrusted = true;

		final CommunicationSpork spork = new CommunicationSpork();

		spork.setTimeStamp(Instant.now());
		publish(spork);
		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(spork));
	}

	@Example
	public void storesAndRelaysASporkSignedByATrustedKey() {
		final CommunicationSpork spork = signed(STORED_AT, data -> { });

		assertStoredAndRelayed(spork, publish(spork));
	}

	@Example
	public void refusesASporkSignedByAnUntrustedKey() throws Exception {
		final CommunicationSpork stored = storeAnOlderSpork();
		final CommunicationSpork spork = spork(STORED_AT.plusSeconds(1));

		spork.sign(new Signature().getPrivateKey());
		publish(spork);
		assertNothingChanged(stored);
	}

	@Example
	public void refusesASporkChangedAfterItWasSigned() {
		final CommunicationSpork stored = storeAnOlderSpork();
		final CommunicationSpork spork = signed(spork(STORED_AT.plusSeconds(1)));

		spork.setTimeStamp(STORED_AT.plusSeconds(2));
		publish(spork);
		assertNothingChanged(stored);
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void refusesASporkThatIsNotNewer(@ForAll("notNewerNanos") long nanosAfterStored) {
		final CommunicationSpork stored = storeAnOlderSpork();

		publish(signed(spork(STORED_AT.plusNanos(nanosAfterStored))));
		assertNothingChanged(stored);
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void replacesAnOlderSporkWithANewerOneAndRelaysIt(@ForAll("newerNanos") long nanosAfterStored) {
		storeAnOlderSpork();

		final CommunicationSpork newer = signed(spork(STORED_AT.plusNanos(nanosAfterStored)));

		assertStoredAndRelayed(newer, publish(newer));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void storesAndRelaysDataAtItsLimits(@ForAll Accepted limit) {
		storeAnOlderSpork();

		final CommunicationSpork spork = signed(STORED_AT.plusSeconds(1), limit.change);

		assertStoredAndRelayed(spork, publish(spork));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void refusesDataBeyondItsLimits(@ForAll Refused limit) {
		final CommunicationSpork stored = storeAnOlderSpork();

		publish(signed(STORED_AT.plusSeconds(1), limit.change));
		assertNothingChanged(stored);
	}

	@Property(tries = 30)
	public void storesTheDataThatValidatesAndRefusesTheRest(@ForAll("sporkDataAcrossTheBounds") SporkData data) {
		final CommunicationSpork stored = storeAnOlderSpork();
		final CommunicationSpork spork = spork(STORED_AT.plusSeconds(1));
		final boolean valid = CommunicationSporkTest.accepts(data);

		spork.setData(data);
		publish(signed(spork));
		assertThat(sporkDatabase.getCommunicationSpork(), sameInstance(valid ? spork : stored));
		assertThat(relayed.size(), is(valid ? 1 : 0));
	}

	@Example
	public void refusesInvalidDataWithoutStoringAnything() {
		publish(signed(STORED_AT, data -> data.setGroupSize(0)));
		assertThat(sporkDatabase.getCommunicationSpork(), is(nullValue()));
		assertThat(relayed, is(empty()));
	}

	@Example
	public void leavesThePreviousDataUnchecked() {
		final CommunicationSpork spork = spork(STORED_AT);
		final SporkData unusable = new SporkData();

		unusable.setGroupSize(0);
		spork.setPreviousData(unusable);
		signed(spork);
		assertStoredAndRelayed(spork, publish(spork));
	}

	@Example
	public void logsTheRefusalWithTheFieldAndValueOnly() {
		final CommunicationSpork spork = signed(STORED_AT, data -> data.setGroupSize(0));
		final List<ILoggingEvent> warnings = warningsWhilePublishing(spork);

		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getLevel(), is(Level.WARN));
		assertThat(warnings.get(0).getFormattedMessage(),
			equalTo("Refusing a communication spork: groupSize must be 1-65535, not 0")
		);
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	@SneakyThrows
	public void logsNothingForInvalidDataFromAnUntrustedKey() {
		final CommunicationSpork spork = spork(STORED_AT);

		spork.<SporkData>getData().setGroupSize(0);
		spork.sign(new Signature().getPrivateKey());
		assertThat(warningsWhilePublishing(spork), is(empty()));
	}

	@Example
	public void storesAndRelaysASporkOfAnotherTypeWithoutCheckingItsData() {
		everySignatureTrusted = true;

		final MintSupply spork = new MintSupply();

		spork.setTimeStamp(STORED_AT);
		final Packet packet = publish(spork);

		assertThat(sporkDatabase.getMintSupply(), sameInstance(spork));
		assertThat(relayed, contains(sameInstance(packet)));
	}

	@Example
	public void relaysACommunicationSporkToNodesOfThisReleaseOnly() throws InterruptedException {
		publish(signed(STORED_AT, data -> { }));

		try (QuicLoopback current = QuicLoopback.between(Network.getProtocols(), Network.getProtocols());
			QuicLoopback previous = QuicLoopback.between(PREVIOUS_RELEASE, Network.getProtocols())) {

			assertThat(receivers, hasSize(1));
			assertThat(receivers.get(0).test(current.stream()), is(true));
			assertThat(receivers.get(0).test(previous.stream()), is(false));
		}
	}

	@Example
	public void relaysASporkOfAnotherTypeToNodesOfThePreviousReleaseToo() throws InterruptedException {
		everySignatureTrusted = true;

		final MintSupply spork = new MintSupply();

		spork.setTimeStamp(STORED_AT);
		publish(spork);

		try (QuicLoopback previous = QuicLoopback.between(PREVIOUS_RELEASE, Network.getProtocols())) {
			assertThat(receivers, hasSize(1));
			assertThat(receivers.get(0).test(previous.stream()), is(true));
		}
	}
}
