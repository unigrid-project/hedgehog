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

package org.unigrid.hedgehog.service.chain;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryCodec;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Tag;

public class ChainHostFloodTest {
	private static final int PACKET_SIZE = ChainCodecs.MAX_ENTRIES_PER_PACKET;
	private static final int MAX_ENTRY_BYTES = 16 * 1024;
	private static final long BEYOND_RETENTION_MILLIS = 31_000;
	private static final String FAILURE_DETAIL = "10.9.8.7:52883 turned the stream down";

	/* An embedded stream that a test can take down after the host holds it, and that notes what the host does to
	   it, as a stream that is closed or full takes the writes without a trace */
	private static final class ScriptedStream extends EmbeddedChannel {
		private final List<Object> writes = new CopyOnWriteArrayList<>();
		private final AtomicInteger flushes = new AtomicInteger();
		private volatile boolean gone;
		private volatile RuntimeException failure;

		@Override
		public boolean isActive() {
			return !gone && super.isActive();
		}

		@Override
		public ChannelFuture write(final Object message) {
			writes.add(message);

			if (failure != null) {
				throw failure;
			}

			return super.write(message);
		}

		@Override
		public Channel flush() {
			flushes.incrementAndGet();
			return super.flush();
		}
	}

	private static List<Entry> entriesOf(final ChainFleet fleet, final String member) {
		return fleet.host(member).chain(fleet.chain()).orElseThrow().entries();
	}

	private static List<Entry> received(final EmbeddedChannel client) {
		final List<Entry> entries = new ArrayList<>();

		for (Object packet = client.readOutbound(); packet != null; packet = client.readOutbound()) {
			entries.addAll(((ChainEntry) packet).getEntries());
		}

		return entries;
	}

	private static List<Entry> write(final ChainFleet fleet, final int count) {
		return ChainFleet.entries(ChainFleet.key(1), fleet.chain(), count, ChainFleet.NOW);
	}

	private static void post(final ChainFleet fleet, final EmbeddedChannel client, final List<Entry> entries) {
		fleet.host("gn-0").receive(client, ChainEntry.builder().entries(entries).build());
		fleet.deliver();
	}

	private static List<EntryRefused> receiveFrom(final ChainHost host, final Channel stream,
		final List<Entry> entries) {

		return host.receive(stream, ChainEntry.builder().entries(entries).build());
	}

	private static List<ChainEntry> packetsOf(final EmbeddedChannel stream) {
		final List<ChainEntry> packets = new ArrayList<>();

		for (Object packet = stream.readOutbound(); packet != null; packet = stream.readOutbound()) {
			packets.add((ChainEntry) packet);
		}

		return packets;
	}

	private static List<Integer> sizesOf(final List<ChainEntry> packets) {
		return packets.stream().map(packet -> packet.getEntries().size()).toList();
	}

	private static List<Entry> entriesIn(final List<ChainEntry> packets) {
		return packets.stream().flatMap(packet -> packet.getEntries().stream()).toList();
	}

	private static Map<String, Long> sentBy(final ChainFleet fleet) {
		return fleet.deliveries.stream()
			.collect(Collectors.groupingBy(ChainFleet.Delivery::from, Collectors.counting()));
	}

	private static List<Entry> writtenBy(final int writer, final ChainId chain, final int count) {
		return ChainFleet.entries(ChainFleet.key(writer), chain, count, ChainFleet.NOW);
	}

	private static Entry foreign() {
		return writtenBy(1, new ChainId(new byte[ChainId.SIZE]), 1).get(0);
	}

	private static Entry oversized(final ChainId chain) {
		return new FeedWriter(ChainFleet.key(1), chain).write(ChainFleet.NOW, ChainFleet.TAG,
			new byte[MAX_ENTRY_BYTES]);
	}

	@SneakyThrows
	private static Entry forged(final Entry genuine) {
		final byte[] bytes = EntryCodec.encode(genuine);

		bytes[bytes.length - 1] ^= 1;
		return EntryCodec.decode(bytes, MAX_ENTRY_BYTES);
	}

	private static List<ScriptedStream> streams() {
		return IntStream.range(0, 3).mapToObj(number -> new ScriptedStream()).toList();
	}

	/* A host of the group of gn-0 that holds the given streams to gn-1, gn-2 and gn-3 and is not part of the fleet,
	   so nothing is handed over unless the test does it */
	private static ChainHost hostHolding(final ChainFleet fleet, final List<ScriptedStream> members) {
		final Map<String, ScriptedStream> byMember = Map.of("gn-1", members.get(0), "gn-2", members.get(1),
			"gn-3", members.get(2));
		final ChainHost host = fleet.hostOver("gn-0", member -> Optional.<Channel>ofNullable(byMember.get(member)));

		host.refresh();
		return host;
	}

	private static ScriptedStream joined(final ChainHost host, final ScriptedStream stream, final int... writers) {
		for (final int writer : writers) {
			final JoinReply reply = host.join(stream, JoinChain.builder().requestId(writer)
				.writer(ChainFleet.writer(writer)).chainId(host.hostedChains().get(0)).build());

			assertThat(reply.getStatus(), is(JoinReply.Status.JOINED));
		}

		return stream;
	}

	private static void assertNotFlooded(final ChainFleet fleet, final Entry entry, final Admission admission) {
		final int before = fleet.stream("gn-0", "gn-1").outboundMessages().size();

		assertThat(fleet.host("gn-0").post(entry), is(admission));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages().size(), is(before));
	}

	private static void assertFloodsNothingFor(final Entry entry) {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel reader = fleet.client("gn-0", 2);

		assertThat(receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), List.of(entry)), is(empty()));

		assertThat(reader.outboundMessages(), is(empty()));
		assertThat(fleet.stream("gn-0", "gn-2").outboundMessages(), is(empty()));
		assertThat(fleet.stream("gn-0", "gn-3").outboundMessages(), is(empty()));
	}

	/* The entries are held back past their first, which then releases all of them at once */
	private static void assertReleasedInPackets(final int count, final List<Integer> sizes) {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-0", 2);
		final List<Entry> entries = write(fleet, count);

		for (int from = 1; from < count; from += PACKET_SIZE) {
			receiveFrom(host, writer, entries.subList(from, Math.min(from + PACKET_SIZE, count)));
		}

		assertThat(reader.outboundMessages(), is(empty()));
		receiveFrom(host, writer, entries.subList(0, 1));

		for (final EmbeddedChannel stream : List.of(reader, fleet.stream("gn-0", "gn-1"))) {
			final List<ChainEntry> packets = packetsOf(stream);

			assertThat(sizesOf(packets), equalTo(sizes));
			assertThat(entriesIn(packets), equalTo(entries));
		}
	}

	private static void assertCarriesOnPast(final int failing) {
		final ChainFleet fleet = ChainFleet.group();
		final List<ScriptedStream> members = streams();
		final ChainHost host = hostHolding(fleet, members);
		final ScriptedStream client = joined(host, new ScriptedStream(), 1);
		final Entry entry = writtenBy(2, host.hostedChains().get(0), 1).get(0);

		members.get(failing).failure = new IllegalStateException(FAILURE_DETAIL);

		final List<ILoggingEvent> warnings = warningsWhile(() -> host.post(entry));

		for (final ScriptedStream stream : members) {
			assertThat(stream.writes, hasSize(1));
		}

		assertThat(client.writes, hasSize(1));
		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(FAILURE_DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	/* The module descriptor reads logback-classic only, so the in-memory appender of logback-core is out of reach */
	private static void readLogbackCore() {
		ChainHostFloodTest.class.getModule()
			.addReads(ModuleLayer.boot().findModule("ch.qos.logback.core").orElseThrow());
	}

	private static List<ILoggingEvent> warningsWhile(final Runnable action) {
		readLogbackCore();

		final Logger logger = (Logger) LoggerFactory.getLogger(ChainHost.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		appender.start();
		logger.addAppender(appender);

		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
		}

		return appender.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
	}

	@Example
	public void floodsNewEntriesToEveryOtherMember() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 5);

		post(fleet, fleet.client("gn-0", 1), entries);

		for (int member = 0; member < ChainFleet.MEMBERS; member++) {
			assertThat(entriesOf(fleet, "gn-" + member), equalTo(entries));
		}
	}

	@Example
	public void neverSendsEntriesBackToTheStreamTheyArrivedOn() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-0", 2);
		final List<Entry> entries = write(fleet, 3);

		post(fleet, writer, entries);

		assertThat(received(writer), is(empty()));
		assertThat(received(reader), equalTo(entries));
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.to().equals("gn-0")).toList(), is(empty()));
	}

	@Example
	public void floodsEachEntryOnceAndOnlyWhileItIsNew() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-2", 2);
		final List<Entry> entries = write(fleet, 3);

		post(fleet, writer, entries);

		final int deliveries = fleet.deliveries.size();

		post(fleet, writer, entries);

		assertThat(fleet.deliveries.size(), is(deliveries));
		assertThat(received(reader), equalTo(entries));
	}

	@Example
	public void doesNotFloodWhatAChainRefused() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-1", 2);
		final List<Entry> entries = write(fleet, 6);

		post(fleet, writer, entries);

		assertThat(received(reader), equalTo(entries.subList(0, 5)));
		assertThat(entriesOf(fleet, "gn-3"), equalTo(entries.subList(0, 5)));
	}

	@Example
	public void floodsNothingForEntriesFromAStreamThatMayNotWriteThem() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel reader = fleet.client("gn-1", 2);

		post(fleet, new EmbeddedChannel(), write(fleet, 3));

		assertThat(fleet.deliveries, is(empty()));
		assertThat(received(reader), is(empty()));
	}

	@Example
	public void floodsTheEntriesAChainReleasesFromItsBufferWithTheOneThatFilledTheGap() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-1", 2);
		final List<Entry> entries = write(fleet, 5);

		post(fleet, writer, entries.subList(1, 5));
		assertThat(fleet.deliveries, is(empty()));

		post(fleet, writer, entries.subList(0, 1));

		assertThat(received(reader), equalTo(entries));
		assertThat(entriesOf(fleet, "gn-3"), equalTo(entries));
	}

	@Example
	public void skipsAStreamThatDoesNotTakeWhatItWasSent() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel slow = ChainFleet.unwritable(fleet.client("gn-0", 2));
		final EmbeddedChannel reader = fleet.client("gn-0", 3);
		final List<Entry> entries = write(fleet, 3);

		post(fleet, writer, entries);

		assertThat(slow.outboundMessages(), is(empty()));
		assertThat(received(reader), equalTo(entries));
		assertThat(entriesOf(fleet, "gn-3"), equalTo(entries));
	}

	@Example
	public void carriesOnWithoutAMemberItHasNoStreamTo() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final List<Entry> entries = write(fleet, 4);

		fleet.offline("gn-3");
		fleet.refreshAll();
		post(fleet, writer, entries);

		assertThat(entriesOf(fleet, "gn-1"), equalTo(entries));
		assertThat(entriesOf(fleet, "gn-2"), equalTo(entries));
		assertThat(entriesOf(fleet, "gn-3"), is(empty()));
	}

	@Example
	public void floodsTheEntriesThisNodeWritesItselfToEveryone() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel client = fleet.client("gn-0", 1);
		final List<Entry> entries = write(fleet, 2);

		entries.forEach(entry -> assertThat(fleet.host("gn-0").post(entry), is(Admission.ACCEPTED)));
		fleet.deliver();

		assertThat(received(client), equalTo(entries));
		assertThat(entriesOf(fleet, "gn-2"), equalTo(entries));
	}

	@Example
	public void refusesToPostToAChainItDoesNotHost() {
		final ChainFleet fleet = ChainFleet.group();
		final Entry foreign = ChainFleet.entries(ChainFleet.key(1), new ChainId(new byte[ChainId.SIZE]), 1,
			ChainFleet.NOW).get(0);

		assertThat(fleet.host("gn-0").post(foreign), is(Admission.WRONG_CHAIN));
	}

	@Property(tries = 10)
	public void floodsALongRunInPacketsTheCodecAccepts(@ForAll @IntRange(min = 1, max = 200) int count) {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-0", 2);

		post(fleet, writer, write(fleet, count));

		assertThat(fleet.deliveries.stream().map(delivery -> ((ChainEntry) delivery.packet()).getEntries().size())
			.collect(Collectors.toList()), everyItem(lessThanOrEqualTo(ChainCodecs.MAX_ENTRIES_PER_PACKET)));
		assertThat(received(reader).size(), is(count));
	}

	@Example
	public void passesAnEntryOnceFromEveryMemberSoAGroupOfFourSendsNineCopies() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel reader = fleet.client("gn-2", 2);

		post(fleet, fleet.client("gn-0", 1), write(fleet, 5));

		assertThat(fleet.deliveries, hasSize(9));
		assertThat(sentBy(fleet), equalTo(Map.of("gn-0", 3L, "gn-1", 2L, "gn-2", 2L, "gn-3", 2L)));
		assertThat(sizesOf(packetsOf(reader)), equalTo(List.of(5)));
	}

	@Example
	public void sendsNineCopiesOfEachOfThreeEntriesPostedOneByOne() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-3", 2);

		write(fleet, 3).forEach(entry -> post(fleet, writer, List.of(entry)));

		assertThat(fleet.deliveries, hasSize(27));
		assertThat(sizesOf(packetsOf(reader)), equalTo(List.of(1, 1, 1)));
	}

	@Example
	public void floodsOnlyWhatIsNewInAPacketThatRepeatsEntriesOfAnotherMember() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 5);

		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries.subList(0, 3));
		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-3"), entries.subList(1, 5));

		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-1"))), equalTo(List.of(2)));
		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-2"))), equalTo(List.of(3, 2)));
		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-3"))), equalTo(List.of(3)));
	}

	@Example
	public void neverSendsEntriesBackToTheMemberTheyArrivedFrom() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel reader = fleet.client("gn-0", 2);
		final List<Entry> entries = write(fleet, 3);

		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries);

		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), is(empty()));
		assertThat(entriesIn(packetsOf(fleet.stream("gn-0", "gn-2"))), equalTo(entries));
		assertThat(entriesIn(packetsOf(fleet.stream("gn-0", "gn-3"))), equalTo(entries));
		assertThat(received(reader), equalTo(entries));
	}

	@Example
	public void floodsNothingForAnEntryThatIsNotAuthentic() {
		assertFloodsNothingFor(forged(write(ChainFleet.group(), 1).get(0)));
	}

	@Example
	public void floodsNothingForAnEntryThatIsTooOldToKeep() {
		final ChainFleet fleet = ChainFleet.group();

		assertFloodsNothingFor(ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1,
			ChainFleet.NOW - BEYOND_RETENTION_MILLIS).get(0));
	}

	@Example
	public void floodsNothingForAnEntryFromTheFuture() {
		final ChainFleet fleet = ChainFleet.group();

		assertFloodsNothingFor(ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1,
			ChainFleet.NOW + BEYOND_RETENTION_MILLIS).get(0));
	}

	@Example
	public void floodsNothingForAnEntryThatIsTooLarge() {
		assertFloodsNothingFor(oversized(ChainFleet.group().chain()));
	}

	@Example
	public void floodsNothingForAnEntryOfAChainItDoesNotHost() {
		assertFloodsNothingFor(foreign());
	}

	@Example
	public void floodsNothingForTheEntryAChainHasNoRoomForAndStillRefusesIt() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final List<Entry> entries = write(fleet, 6);

		final List<EntryRefused> refusals = receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries);

		assertThat(refusals, equalTo(List.of(EntryRefused.of(entries.get(5), Admission.FULL))));
		assertThat(entriesIn(packetsOf(fleet.stream("gn-0", "gn-2"))), equalTo(entries.subList(0, 5)));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), is(empty()));
	}

	@Example
	public void floodsNothingForWhatItAlreadyHasOnceItFloodedItBefore() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 3);

		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries);
		fleet.stream("gn-0", "gn-2").outboundMessages().clear();
		fleet.stream("gn-0", "gn-3").outboundMessages().clear();
		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries);
		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-2"), entries);

		for (final String member : List.of("gn-1", "gn-2", "gn-3")) {
			assertThat(fleet.stream("gn-0", member).outboundMessages(), is(empty()));
		}
	}

	@Example
	public void floodsWhatWaitedAheadOfAGapExactlyOnceWhenTheGapFills() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 4);

		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries.subList(2, 4));
		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries.subList(2, 4));
		assertThat(fleet.stream("gn-0", "gn-2").outboundMessages(), is(empty()));

		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-3"), entries.subList(0, 2));
		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"), entries.subList(2, 4));

		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-2"))), equalTo(List.of(4)));
		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-1"))), equalTo(List.of(4)));
		assertThat(fleet.stream("gn-0", "gn-3").outboundMessages(), is(empty()));
	}

	@Example
	public void floodsARunOf64ReleasedFromTheBufferInOnePacket() {
		assertReleasedInPackets(64, List.of(64));
	}

	@Example
	public void floodsARunOf65ReleasedFromTheBufferInTwoPackets() {
		assertReleasedInPackets(65, List.of(64, 1));
	}

	@Example
	public void floodsARunOf128ReleasedFromTheBufferInTwoFullPackets() {
		assertReleasedInPackets(128, List.of(64, 64));
	}

	@Example
	public void floodsARunOf129ReleasedFromTheBufferInThreePackets() {
		assertReleasedInPackets(129, List.of(64, 64, 1));
	}

	@Example
	public void floodsASingleEntryInAPacketOfItsOwn() {
		assertReleasedInPackets(1, List.of(1));
	}

	@Example
	public void floodsTheEntriesOfOnePacketTogetherAndNotOneByOne() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel reader = fleet.client("gn-0", 2);
		final List<Entry> entries = write(fleet, PACKET_SIZE);

		receiveFrom(fleet.host("gn-0"), writer, entries);

		assertThat(sizesOf(packetsOf(reader)), equalTo(List.of(64)));
		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-2"))), equalTo(List.of(64)));
	}

	@Example
	public void floodsWhatSeveralWritersGetAppendedInOnePacketTogether() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel reader = fleet.client("gn-0", 3);
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final List<Entry> one = writtenBy(1, fleet.chain(), 41);
		final List<Entry> two = writtenBy(2, fleet.chain(), 41);

		receiveFrom(host, member, one.subList(1, 41));
		receiveFrom(host, member, two.subList(1, 41));
		assertThat(reader.outboundMessages(), is(empty()));

		receiveFrom(host, member, List.of(one.get(0), two.get(0)));

		final List<ChainEntry> packets = packetsOf(reader);

		assertThat(sizesOf(packets), equalTo(List.of(64, 18)));
		assertThat(entriesIn(packets), equalTo(Stream.concat(one.stream(), two.stream()).toList()));
	}

	@Example
	public void floodsEachChainToItsOwnClientsAndItsEntriesTogether() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId other = fleet.host("gn-0").hostedChains().get(1);
		final EmbeddedChannel ofFirst = fleet.client("gn-0", 3);
		final EmbeddedChannel ofOther = new EmbeddedChannel();
		final List<Entry> firsts = write(fleet, 2);
		final List<Entry> others = writtenBy(1, other, 2);

		fleet.host("gn-0").join(ofOther, JoinChain.builder().requestId(4).writer(ChainFleet.writer(4)).chainId(other)
			.build());
		receiveFrom(fleet.host("gn-0"), fleet.stream("gn-0", "gn-1"),
			List.of(firsts.get(0), others.get(0), firsts.get(1), others.get(1)));

		assertThat(packetsOf(ofFirst).stream().map(ChainEntry::getEntries).toList(), equalTo(List.of(firsts)));
		assertThat(packetsOf(ofOther).stream().map(ChainEntry::getEntries).toList(), equalTo(List.of(others)));
		assertThat(packetsOf(fleet.stream("gn-0", "gn-2")).stream().map(ChainEntry::getEntries).toList(),
			equalTo(List.of(firsts, others)));
	}

	@Example
	public void skipsAMemberStreamAndAClientThatDoNotTakeWhatTheyAreSent() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel writer = fleet.client("gn-0", 1);
		final EmbeddedChannel slowClient = ChainFleet.unwritable(fleet.client("gn-0", 2));
		final EmbeddedChannel slowMember = ChainFleet.unwritable(fleet.stream("gn-0", "gn-2"));

		receiveFrom(fleet.host("gn-0"), writer, write(fleet, 3));

		assertThat(slowClient.outboundMessages(), is(empty()));
		assertThat(slowMember.outboundMessages(), is(empty()));
		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-1"))), equalTo(List.of(3)));
		assertThat(sizesOf(packetsOf(fleet.stream("gn-0", "gn-3"))), equalTo(List.of(3)));
	}

	@Example
	public void skipsAMemberStreamAndAClientThatAreNotActive() {
		final ChainFleet fleet = ChainFleet.group();
		final List<ScriptedStream> members = streams();
		final ChainHost host = hostHolding(fleet, members);
		final ScriptedStream open = joined(host, new ScriptedStream(), 1);
		final ScriptedStream closed = joined(host, new ScriptedStream(), 2);

		members.get(0).gone = true;
		closed.gone = true;

		assertThat(host.post(writtenBy(3, host.hostedChains().get(0), 1).get(0)), is(Admission.ACCEPTED));

		assertThat(members.get(0).writes, is(empty()));
		assertThat(closed.writes, is(empty()));
		assertThat(members.get(1).writes, hasSize(1));
		assertThat(members.get(2).writes, hasSize(1));
		assertThat(open.writes, hasSize(1));
	}

	@Example
	public void carriesOnPastAFirstMemberStreamThatFails() {
		assertCarriesOnPast(0);
	}

	@Example
	public void carriesOnPastAMiddleMemberStreamThatFails() {
		assertCarriesOnPast(1);
	}

	@Example
	public void carriesOnPastALastMemberStreamThatFails() {
		assertCarriesOnPast(2);
	}

	@Example
	public void floodsOnceToAStreamThatIsBothAMemberAndAClient() {
		final ChainFleet fleet = ChainFleet.group();
		final List<ScriptedStream> members = streams();
		final ChainHost host = hostHolding(fleet, members);

		joined(host, members.get(0), 1);
		host.post(writtenBy(3, host.hostedChains().get(0), 1).get(0));

		assertThat(members.get(0).writes, hasSize(1));
		assertThat(members.get(0).flushes.get(), is(1));
	}

	@Example
	public void floodsOnceToAStreamThatCarriesSeveralWriters() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = hostHolding(fleet, streams());
		final ScriptedStream client = joined(host, new ScriptedStream(), 1, 2, 3);

		host.post(writtenBy(4, host.hostedChains().get(0), 1).get(0));

		assertThat(client.writes, hasSize(1));
		assertThat(client.flushes.get(), is(1));
	}

	@Example
	public void flushesNobodyForAnEntryItAlreadyHad() {
		final ChainFleet fleet = ChainFleet.group();
		final List<ScriptedStream> members = streams();
		final ChainHost host = hostHolding(fleet, members);
		final ScriptedStream client = joined(host, new ScriptedStream(), 1);
		final Entry entry = writtenBy(2, host.hostedChains().get(0), 1).get(0);

		assertThat(host.post(entry), is(Admission.ACCEPTED));
		assertThat(host.post(entry), is(Admission.DUPLICATE));

		for (final ScriptedStream stream : List.of(members.get(0), members.get(1), members.get(2), client)) {
			assertThat(stream.writes, hasSize(1));
			assertThat(stream.flushes.get(), is(1));
		}
	}

	@Example
	public void floodsAPostToEveryMemberStreamAndClientWithNoneSpared() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel first = fleet.client("gn-0", 1);
		final EmbeddedChannel second = fleet.client("gn-0", 2);
		final List<Entry> entries = write(fleet, 2);

		entries.forEach(host::post);

		for (final String member : List.of("gn-1", "gn-2", "gn-3")) {
			final List<ChainEntry> packets = packetsOf(fleet.stream("gn-0", member));

			assertThat(sizesOf(packets), equalTo(List.of(1, 1)));
			assertThat(entriesIn(packets), equalTo(entries));
		}

		assertThat(received(first), equalTo(entries));
		assertThat(received(second), equalTo(entries));
	}

	@Example
	public void answersAPostWithWhyTheChainTurnedItDownAndFloodsNothingForIt() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chain = fleet.chain();
		final Entry taken = writtenBy(1, chain, 1).get(0);

		assertThat(fleet.host("gn-0").post(taken), is(Admission.ACCEPTED));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), hasSize(1));

		assertNotFlooded(fleet, taken, Admission.DUPLICATE);
		assertNotFlooded(fleet, writtenBy(2, chain, 2).get(1), Admission.AHEAD);
		assertNotFlooded(fleet, forged(writtenBy(3, chain, 1).get(0)), Admission.NOT_AUTHENTIC);
		assertNotFlooded(fleet, ChainFleet.entries(ChainFleet.key(4), chain, 1,
			ChainFleet.NOW - BEYOND_RETENTION_MILLIS).get(0), Admission.EXPIRED);
		assertNotFlooded(fleet, ChainFleet.entries(ChainFleet.key(5), chain, 1,
			ChainFleet.NOW + BEYOND_RETENTION_MILLIS).get(0), Admission.FUTURE);
		assertNotFlooded(fleet, oversized(chain), Admission.TOO_LARGE);
		assertNotFlooded(fleet, foreign(), Admission.WRONG_CHAIN);
	}

	@Example
	public void tellsAPostThatTheChainHasNoRoomAndFloodsNothingForIt() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final List<Entry> entries = write(fleet, 6);

		entries.subList(0, 5).forEach(entry -> assertThat(fleet.host("gn-0").post(entry), is(Admission.ACCEPTED)));
		assertNotFlooded(fleet, entries.get(5), Admission.FULL);
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), hasSize(5));
	}

	@Example
	public void floodsAPostedEntryTogetherWithTheOnesItReleasesFromTheBuffer() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = write(fleet, 3);

		assertThat(host.post(entries.get(2)), is(Admission.AHEAD));
		assertThat(host.post(entries.get(1)), is(Admission.AHEAD));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), is(empty()));

		assertThat(host.post(entries.get(0)), is(Admission.ACCEPTED));

		final List<ChainEntry> packets = packetsOf(fleet.stream("gn-0", "gn-1"));

		assertThat(sizesOf(packets), equalTo(List.of(3)));
		assertThat(entriesIn(packets), equalTo(entries));
	}

	@Example
	public void answersAPostOfAChainItDoesNotHostWithWrongChainAndWritesNothing() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel client = fleet.client("gn-0", 1);

		assertThat(fleet.host("gn-0").post(foreign()), is(Admission.WRONG_CHAIN));

		assertThat(client.outboundMessages(), is(empty()));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), is(empty()));
	}

	@Example
	public void answersAPostWithWrongChainWhileTheProxyIsOff() {
		final ChainFleet fleet = ChainFleet.group();
		final Entry entry = write(fleet, 1).get(0);

		fleet.settings.set(Optional.empty());
		fleet.refreshAll();

		assertThat(fleet.host("gn-0").post(entry), is(Admission.WRONG_CHAIN));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), is(empty()));
	}

	@Example
	public void floodsNothingForAWriterTheChainTakesNoMoreOf() {
		final ChainFleet fleet = ChainFleet.group();
		final List<ScriptedStream> members = streams();
		final ChainHost host = hostHolding(fleet, members);
		final ChainId chain = host.hostedChains().get(0);
		final List<Entry> firsts = IntStream.rangeClosed(0, HostedChain.MAX_WRITERS)
			.mapToObj(writer -> writtenBy(writer, chain, 1).get(0)).toList();
		final Entry beyond = firsts.get(HostedChain.MAX_WRITERS);

		for (int from = 0; from < HostedChain.MAX_WRITERS; from += PACKET_SIZE) {
			receiveFrom(host, members.get(0), firsts.subList(from, from + PACKET_SIZE));
		}

		final int fill = HostedChain.MAX_WRITERS / PACKET_SIZE;

		assertThat(members.get(1).writes, hasSize(fill));
		assertThat(host.post(beyond), is(Admission.FULL));

		/* A stream may send only so many entries in a second, so what follows the fill comes in the next one */
		fleet.clock.addAndGet(1000);

		assertThat(receiveFrom(host, members.get(0), List.of(beyond)), is(empty()));
		assertThat(members.get(1).writes, hasSize(fill));

		assertThat(host.post(writtenBy(0, chain, 2).get(1)), is(Admission.ACCEPTED));
		assertThat(members.get(1).writes, hasSize(fill + 1));
	}

	@Example
	public void floodsToTheStreamsOfTheLatestRefresh() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final List<Entry> entries = write(fleet, 2);

		fleet.offline("gn-3");
		fleet.refreshAll();
		host.post(entries.get(0));

		assertThat(fleet.stream("gn-0", "gn-3").outboundMessages(), is(empty()));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), hasSize(1));

		fleet.online("gn-3");
		fleet.refreshAll();
		host.post(entries.get(1));

		assertThat(fleet.stream("gn-0", "gn-3").outboundMessages(), hasSize(1));
		assertThat(fleet.stream("gn-0", "gn-1").outboundMessages(), hasSize(2));
	}

	/* An exit passes over the member it first heard the entries of a client from, as the client attached through that
	   member. Only a member stream counts, as the stream of a client is no place to pass entries on to, and the
	   record goes with the feed once it ended and is forgotten. */
	@Example
	public void recordsTheMemberThatFirstFloodedAWriterUntilItsFeedIsForgotten() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel client = fleet.client("gn-0", 1);
		final FeedWriter feed = new FeedWriter(ChainFleet.key(1), fleet.chain());
		final List<Entry> first = ChainFleet.write(feed, 1, ChainFleet.NOW);
		final Entry last = feed.writeLast(ChainFleet.NOW, ChainFleet.TAG, ChainFleet.padded(new byte[] { 1 },
			MAX_ENTRY_BYTES));
		final HostedChain attached = fleet.host("gn-0").hosted(fleet.chain()).orElseThrow();
		final HostedChain other = fleet.host("gn-2").hosted(fleet.chain()).orElseThrow();

		post(fleet, client, first);
		receiveFrom(fleet.host("gn-2"), fleet.stream("gn-2", "gn-3"), List.of(last));

		assertThat(other.getChain().heads().get(feed.writer()).last(), is(true));
		assertThat(other.firstFrom(feed.writer()), is(Optional.of(fleet.stream("gn-2", "gn-0"))));
		assertThat(attached.firstFrom(feed.writer()), is(Optional.empty()));

		fleet.clock.addAndGet(BEYOND_RETENTION_MILLIS);
		fleet.host("gn-2").maintain();

		assertThat(other.firstFrom(feed.writer()), is(Optional.empty()));
	}
}
