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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.ShrinkingMode;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.network.codec.ChainArbitraries;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.service.chain.ChainHost;
import org.unigrid.hedgehog.service.chain.ChainService;

public class ChainTrafficHandlerTest extends BaseMockedWeldTest {
	private record Call(Channel peer, Packet packet) {
		/* One packet a handler handed to the chain service, with the stream it arrived on */
	}

	private final List<Call> calls = new CopyOnWriteArrayList<>();
	private volatile List<Entry> retained = List.of();
	private volatile RuntimeException failure;

	/* Starts the container the handlers resolve the service from */
	@Inject
	private ChainService chainService;

	@Provide
	public Arbitrary<ChainId> chainIds() {
		return ChainArbitraries.chainIds();
	}

	@Provide
	public Arbitrary<WriterId> writers() {
		return ChainArbitraries.writers();
	}

	@Provide
	public Arbitrary<List<Entry>> entryLists() {
		return ChainArbitraries.entryLists();
	}

	@Provide
	public Arbitrary<List<Entry>> oneEntry() {
		return entriesOf(1);
	}

	@Provide
	public Arbitrary<List<Entry>> severalEntries() {
		return ChainArbitraries.entries().list().ofMinSize(2).ofMaxSize(8);
	}

	@Provide
	public Arbitrary<List<Entry>> manyEntries() {
		return ChainArbitraries.entries().list().ofMaxSize(3 * ChainCodecs.MAX_ENTRIES_PER_PACKET);
	}

	@Provide
	public Arbitrary<List<Entry>> aFullPacket() {
		return entriesOf(ChainCodecs.MAX_ENTRIES_PER_PACKET);
	}

	@Provide
	public Arbitrary<List<Entry>> aFullPacketAndOne() {
		return entriesOf(ChainCodecs.MAX_ENTRIES_PER_PACKET + 1);
	}

	@Provide
	public Arbitrary<List<Entry>> twoFullPacketsAndOne() {
		return entriesOf(2 * ChainCodecs.MAX_ENTRIES_PER_PACKET + 1);
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> heads() {
		return ChainArbitraries.heads();
	}

	@Provide
	public Arbitrary<Map<WriterId, Head>> someHeads() {
		return ChainArbitraries.heads().filter(heads -> !heads.isEmpty());
	}

	private static Arbitrary<List<Entry>> entriesOf(final int size) {
		return ChainArbitraries.entries().list().ofSize(size).uniqueElements();
	}

	private static List<EntryRefused> refusalsOf(final ChainEntry packet) {
		return packet.getEntries().stream().map(entry -> EntryRefused.of(entry, Admission.FULL))
			.collect(Collectors.toList());
	}

	private static Optional<FetchEntries> fetchFor(final ChainHeads heads) {
		final List<FetchEntries.Range> ranges = heads.getHeads().entrySet().stream()
			.map(head -> new FetchEntries.Range(head.getKey(), 0, head.getValue().seq()))
			.collect(Collectors.toList());

		return ranges.isEmpty() ? Optional.empty()
			: Optional.of(FetchEntries.builder().chainId(heads.getChainId()).ranges(ranges).build());
	}

	private static ChainEntry packetOf(final List<Entry> entries) {
		return ChainEntry.builder().entries(entries).build();
	}

	private static void assertWritten(final EmbeddedChannel channel, final List<?> expected) {
		assertThat(new ArrayList<Object>(channel.outboundMessages()), equalTo(new ArrayList<Object>(expected)));
	}

	private static byte[] filled(final int size, final int value) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	private static ChannelHandler refusingWrites() {
		return new ChannelOutboundHandlerAdapter() {
			@Override
			public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise) {
				promise.setFailure(new IOException("The stream takes no more writes"));
			}
		};
	}

	private void assertPassesOn(final ChannelHandler handler, final Packet... others) {
		final EmbeddedChannel channel = new EmbeddedChannel(handler);

		channel.writeInbound((Object[]) others);

		for (final Packet other : others) {
			assertThat(channel.readInbound(), sameInstance(other));
		}

		assertThat(channel.inboundMessages(), empty());
		assertThat(channel.outboundMessages(), empty());
		assertThat(calls, empty());
	}

	private void failWhenAsked() {
		if (failure != null) {
			throw failure;
		}
	}

	@BeforeProperty
	private void recordTraffic() {
		new MockUp<ChainHost>() {
			@Mock public List<EntryRefused> receive(Channel peer, ChainEntry entries) {
				calls.add(new Call(peer, entries));
				failWhenAsked();
				return refusalsOf(entries);
			}

			@Mock public Optional<FetchEntries> reconcile(Channel peer, ChainHeads heads) {
				calls.add(new Call(peer, heads));
				failWhenAsked();
				return fetchFor(heads);
			}

			@Mock public List<Entry> fetch(Channel peer, FetchEntries request) {
				calls.add(new Call(peer, request));
				failWhenAsked();
				return retained;
			}

			@Mock public void refused(Channel peer, EntryRefused refusal) {
				calls.add(new Call(peer, refusal));
				failWhenAsked();
			}
		};
	}

	@BeforeTry
	private void forgetEarlierTries() {
		calls.clear();
		retained = List.of();
		failure = null;
	}

	@Property(tries = 10)
	public void answersEntriesWithTheRefusalsOfTheService(@ForAll("entryLists") List<Entry> entries) {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChainEntryChannelHandler());
		final ChainEntry packet = ChainEntry.builder().entries(entries).build();

		channel.writeInbound(packet);

		assertThat(calls, contains(new Call(channel, packet)));
		assertWritten(channel, refusalsOf(packet));
	}

	@Property(tries = 10)
	public void asksForWhatTheHeadsShowMissing(@ForAll("chainIds") ChainId chainId,
		@ForAll("heads") Map<WriterId, Head> heads) {

		final EmbeddedChannel channel = new EmbeddedChannel(new ChainHeadsChannelHandler());
		final ChainHeads packet = ChainHeads.builder().chainId(chainId).heads(heads).build();

		channel.writeInbound(packet);

		assertThat(calls, contains(new Call(channel, packet)));
		assertWritten(channel, fetchFor(packet).stream().toList());
	}

	@Property(tries = 10)
	public void answersAFetchInBoundedBatches(@ForAll("chainIds") ChainId chainId,
		@ForAll("manyEntries") List<Entry> entries) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FetchEntriesChannelHandler());
		final FetchEntries request = FetchEntries.builder().chainId(chainId).build();

		retained = entries;
		channel.writeInbound(request);

		assertThat(calls, contains(new Call(channel, request)));
		assertWritten(channel, ChainEntry.batches(entries));
	}

	@Property(tries = 10)
	public void handsARefusalToTheServiceWithoutAnswering(@ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId writer, @ForAll @LongRange(min = 0) long seq, @ForAll Admission admission) {

		final EmbeddedChannel channel = new EmbeddedChannel(new EntryRefusedChannelHandler());
		final EntryRefused refusal = EntryRefused.builder().chainId(chainId).writer(writer).seq(seq)
			.admission(admission).build();

		channel.writeInbound(refusal);

		assertThat(calls, contains(new Call(channel, refusal)));
		assertWritten(channel, List.of());
	}

	@Example
	public void writesNoRefusalWhenThePacketHoldsNoEntry() {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChainEntryChannelHandler());
		final ChainEntry packet = packetOf(List.of());

		channel.writeInbound(packet);

		assertThat(calls, contains(new Call(channel, packet)));
		assertThat(channel.isOpen(), is(true));
		assertWritten(channel, List.of());
	}

	@Property(tries = 10)
	public void writesEveryRefusalInTheOrderOfTheEntries(@ForAll("severalEntries") List<Entry> entries) {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChainEntryChannelHandler());
		final ChainEntry packet = packetOf(entries);

		channel.writeInbound(packet);

		assertWritten(channel, refusalsOf(packet));
	}

	@Property(tries = 10)
	public void answersEachStreamWithItsOwnRefusals(@ForAll("severalEntries") List<Entry> first,
		@ForAll("severalEntries") List<Entry> second) {

		final ChainEntryChannelHandler shared = new ChainEntryChannelHandler();
		final EmbeddedChannel one = new EmbeddedChannel(shared);
		final EmbeddedChannel other = new EmbeddedChannel(shared);
		final ChainEntry fromOne = packetOf(first);
		final ChainEntry fromOther = packetOf(second);

		other.writeInbound(fromOther);
		one.writeInbound(fromOne);

		assertThat(calls, contains(new Call(other, fromOther), new Call(one, fromOne)));
		assertWritten(one, refusalsOf(fromOne));
		assertWritten(other, refusalsOf(fromOther));
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheServiceFailsOnEntries(@ForAll("severalEntries") List<Entry> entries) {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChainEntryChannelHandler());

		failure = new IllegalStateException("The chain service failed");
		channel.writeInbound(packetOf(entries));

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenARefusalCannotBeWritten(@ForAll("oneEntry") List<Entry> entries) {
		final EmbeddedChannel channel = new EmbeddedChannel(refusingWrites(), new ChainEntryChannelHandler());

		channel.writeInbound(packetOf(entries));

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Example
	public void asksForTheExactRangeOfTheHead() {
		final ChainId chain = new ChainId(filled(ChainId.SIZE, 1));
		final WriterId writer = new WriterId(filled(WriterId.SIZE, 2));
		final EmbeddedChannel channel = new EmbeddedChannel(new ChainHeadsChannelHandler());
		final ChainHeads packet = ChainHeads.builder().chainId(chain)
			.heads(Map.of(writer, new Head(Long.MAX_VALUE, -1))).build();

		channel.writeInbound(packet);

		assertThat(calls, contains(new Call(channel, packet)));
		assertWritten(channel, List.of(FetchEntries.builder().chainId(chain)
			.ranges(List.of(new FetchEntries.Range(writer, 0, Long.MAX_VALUE))).build()));
	}

	@Property(tries = 10)
	public void writesNothingWhenTheServiceAsksForNothing(@ForAll("chainIds") ChainId chainId) {
		final EmbeddedChannel channel = new EmbeddedChannel(new ChainHeadsChannelHandler());
		final ChainHeads packet = ChainHeads.builder().chainId(chainId).build();

		channel.writeInbound(packet);

		assertThat(calls, contains(new Call(channel, packet)));
		assertThat(channel.isOpen(), is(true));
		assertWritten(channel, List.of());
	}

	@Property(tries = 10)
	public void answersEachStreamWithItsOwnFetch(@ForAll("chainIds") ChainId first, @ForAll("chainIds") ChainId second,
		@ForAll("someHeads") Map<WriterId, Head> firstHeads, @ForAll("someHeads") Map<WriterId, Head> secondHeads) {

		final ChainHeadsChannelHandler shared = new ChainHeadsChannelHandler();
		final EmbeddedChannel one = new EmbeddedChannel(shared);
		final EmbeddedChannel other = new EmbeddedChannel(shared);
		final ChainHeads fromOne = ChainHeads.builder().chainId(first).heads(firstHeads).build();
		final ChainHeads fromOther = ChainHeads.builder().chainId(second).heads(secondHeads).build();

		other.writeInbound(fromOther);
		one.writeInbound(fromOne);

		assertThat(calls, contains(new Call(other, fromOther), new Call(one, fromOne)));
		assertWritten(one, fetchFor(fromOne).stream().toList());
		assertWritten(other, fetchFor(fromOther).stream().toList());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheServiceFailsOnHeads(@ForAll("chainIds") ChainId chainId,
		@ForAll("someHeads") Map<WriterId, Head> heads) {

		final EmbeddedChannel channel = new EmbeddedChannel(new ChainHeadsChannelHandler());

		failure = new IllegalStateException("The chain service failed");
		channel.writeInbound(ChainHeads.builder().chainId(chainId).heads(heads).build());

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheFetchCannotBeWritten(@ForAll("chainIds") ChainId chainId,
		@ForAll("someHeads") Map<WriterId, Head> heads) {

		final EmbeddedChannel channel = new EmbeddedChannel(refusingWrites(), new ChainHeadsChannelHandler());

		channel.writeInbound(ChainHeads.builder().chainId(chainId).heads(heads).build());

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void writesNothingWhenNothingIsRetained(@ForAll("chainIds") ChainId chainId) {
		final EmbeddedChannel channel = new EmbeddedChannel(new FetchEntriesChannelHandler());
		final FetchEntries request = FetchEntries.builder().chainId(chainId).build();

		channel.writeInbound(request);

		assertThat(calls, contains(new Call(channel, request)));
		assertThat(channel.isOpen(), is(true));
		assertWritten(channel, List.of());
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void writesAFullPacketAsOnePacket(@ForAll("chainIds") ChainId chainId,
		@ForAll("aFullPacket") List<Entry> entries) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FetchEntriesChannelHandler());

		retained = entries;
		channel.writeInbound(FetchEntries.builder().chainId(chainId).build());

		assertThat(entries.size(), is(ChainCodecs.MAX_ENTRIES_PER_PACKET));
		assertWritten(channel, List.of(packetOf(entries)));
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void writesTheEntryOverAFullPacketInASecondPacket(@ForAll("chainIds") ChainId chainId,
		@ForAll("aFullPacketAndOne") List<Entry> entries) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FetchEntriesChannelHandler());
		final int full = ChainCodecs.MAX_ENTRIES_PER_PACKET;

		retained = entries;
		channel.writeInbound(FetchEntries.builder().chainId(chainId).build());

		assertThat(entries.size(), is(full + 1));
		assertWritten(channel, List.of(packetOf(entries.subList(0, full)), packetOf(entries.subList(full, full + 1))));
	}

	@Property(tries = 3, shrinking = ShrinkingMode.OFF)
	public void keepsTheOrderOfEntriesAcrossPackets(@ForAll("chainIds") ChainId chainId,
		@ForAll("twoFullPacketsAndOne") List<Entry> entries) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FetchEntriesChannelHandler());
		final int full = ChainCodecs.MAX_ENTRIES_PER_PACKET;

		retained = entries;
		channel.writeInbound(FetchEntries.builder().chainId(chainId).build());

		assertThat(entries.size(), is(2 * full + 1));
		assertWritten(channel, List.of(packetOf(entries.subList(0, full)),
			packetOf(entries.subList(full, 2 * full)), packetOf(entries.subList(2 * full, 2 * full + 1))));
	}

	@Property(tries = 10)
	public void answersEachStreamWithItsOwnEntriesToFetch(@ForAll("chainIds") ChainId chainId,
		@ForAll("severalEntries") List<Entry> first, @ForAll("severalEntries") List<Entry> second) {

		final FetchEntriesChannelHandler shared = new FetchEntriesChannelHandler();
		final EmbeddedChannel one = new EmbeddedChannel(shared);
		final EmbeddedChannel other = new EmbeddedChannel(shared);
		final FetchEntries fromOne = FetchEntries.builder().chainId(chainId).build();
		final FetchEntries fromOther = FetchEntries.builder().chainId(chainId).build();

		retained = second;
		other.writeInbound(fromOther);
		retained = first;
		one.writeInbound(fromOne);

		assertThat(calls, contains(new Call(other, fromOther), new Call(one, fromOne)));
		assertWritten(one, List.of(packetOf(first)));
		assertWritten(other, List.of(packetOf(second)));
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheServiceFailsOnAFetch(@ForAll("chainIds") ChainId chainId,
		@ForAll("severalEntries") List<Entry> entries) {

		final EmbeddedChannel channel = new EmbeddedChannel(new FetchEntriesChannelHandler());

		retained = entries;
		failure = new IllegalStateException("The chain service failed");
		channel.writeInbound(FetchEntries.builder().chainId(chainId).build());

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheEntriesCannotBeWritten(@ForAll("chainIds") ChainId chainId,
		@ForAll("severalEntries") List<Entry> entries) {

		final EmbeddedChannel channel = new EmbeddedChannel(refusingWrites(), new FetchEntriesChannelHandler());

		retained = entries;
		channel.writeInbound(FetchEntries.builder().chainId(chainId).build());

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Example
	public void handsOverARefusalOfAnyAdmissionUnchanged() {
		final ChainId chain = new ChainId(filled(ChainId.SIZE, 3));
		final WriterId writer = new WriterId(filled(WriterId.SIZE, 4));
		final List<Call> expected = new ArrayList<>();

		for (final Admission admission : Admission.values()) {
			final EmbeddedChannel channel = new EmbeddedChannel(new EntryRefusedChannelHandler());
			final EntryRefused refusal = new EntryRefused(chain, writer, Long.MAX_VALUE, admission);

			channel.writeInbound(refusal);

			expected.add(new Call(channel, refusal));
			assertThat(channel.isOpen(), is(true));
			assertThat(channel.outboundMessages(), empty());
		}

		assertThat(calls, equalTo(expected));
	}

	@Property(tries = 10)
	public void handsEachStreamItsOwnRefusal(@ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId first, @ForAll("writers") WriterId second, @ForAll Admission admission) {

		final EntryRefusedChannelHandler shared = new EntryRefusedChannelHandler();
		final EmbeddedChannel one = new EmbeddedChannel(shared);
		final EmbeddedChannel other = new EmbeddedChannel(shared);
		final EntryRefused fromOne = new EntryRefused(chainId, first, 0, admission);
		final EntryRefused fromOther = new EntryRefused(chainId, second, 1, admission);

		other.writeInbound(fromOther);
		one.writeInbound(fromOne);

		assertThat(calls, contains(new Call(other, fromOther), new Call(one, fromOne)));
		assertThat(one.outboundMessages(), empty());
		assertThat(other.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void closesTheStreamWhenTheServiceFailsOnARefusal(@ForAll("chainIds") ChainId chainId,
		@ForAll("writers") WriterId writer, @ForAll Admission admission) {

		final EmbeddedChannel channel = new EmbeddedChannel(new EntryRefusedChannelHandler());

		failure = new IllegalStateException("The chain service failed");
		channel.writeInbound(new EntryRefused(chainId, writer, 0, admission));

		assertThat(channel.isOpen(), is(false));
		assertThat(channel.outboundMessages(), empty());
	}

	@Property(tries = 10)
	public void passesOtherPacketsOn(@ForAll("chainIds") ChainId chainId, @ForAll("writers") WriterId writer,
		@ForAll("entryLists") List<Entry> entries) {

		final ChainEntry entry = packetOf(entries);
		final ChainHeads heads = ChainHeads.builder().chainId(chainId).build();
		final FetchEntries fetch = FetchEntries.builder().chainId(chainId).build();
		final EntryRefused refusal = new EntryRefused(chainId, writer, 0, Admission.FULL);
		final LeaveChain leave = LeaveChain.builder().chainId(chainId).writer(writer).build();

		assertPassesOn(new ChainEntryChannelHandler(), heads, fetch, refusal, leave);
		assertPassesOn(new ChainHeadsChannelHandler(), entry, fetch, refusal, leave);
		assertPassesOn(new FetchEntriesChannelHandler(), entry, heads, refusal, leave);
		assertPassesOn(new EntryRefusedChannelHandler(), entry, heads, fetch, leave);
	}

	@Property(tries = 10)
	public void routesEachPacketToItsOwnHandler(@ForAll("chainIds") ChainId chainId, @ForAll("writers") WriterId writer,
		@ForAll("severalEntries") List<Entry> entries, @ForAll("someHeads") Map<WriterId, Head> heads) {

		final EmbeddedChannel channel = new EmbeddedChannel(new ChainEntryChannelHandler(),
			new ChainHeadsChannelHandler(), new FetchEntriesChannelHandler(), new EntryRefusedChannelHandler());
		final ChainEntry entry = packetOf(entries);
		final ChainHeads shown = ChainHeads.builder().chainId(chainId).heads(heads).build();
		final FetchEntries request = FetchEntries.builder().chainId(chainId).build();
		final EntryRefused refusal = new EntryRefused(chainId, writer, 0, Admission.FULL);

		retained = entries;
		channel.writeInbound(refusal, request, shown, entry);

		assertThat(calls, contains(new Call(channel, refusal), new Call(channel, request),
			new Call(channel, shown), new Call(channel, entry)));

		final List<Object> expected = new ArrayList<>(ChainEntry.batches(entries));

		expected.addAll(fetchFor(shown).stream().toList());
		expected.addAll(refusalsOf(entry));
		assertWritten(channel, expected);
		assertThat(channel.inboundMessages(), empty());
	}
}
