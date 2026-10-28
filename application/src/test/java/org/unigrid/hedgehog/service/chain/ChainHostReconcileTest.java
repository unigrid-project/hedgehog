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

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.Tag;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostReconcileTest {
	private static final int ROUNDS = 60;
	private static final int ANNOUNCEMENTS = 60;
	private static final int PACKET = ChainCodecs.MAX_ENTRIES_PER_PACKET;
	private static final int FRAME = ChainCodecs.MAX_FRAME_BYTES;
	private static final int MEBIBYTE = 1 << 20;
	private static final String FAILURE_DETAIL = "10.9.8.7:52883 turned the stream down";
	private static final Comparator<Entry> BY_WRITER_THEN_SEQ = Comparator
		.comparing((Entry entry) -> entry.header().writer()).thenComparingLong(entry -> entry.header().seq());

	/* A stream to a member that a test can take out of use after the host holds it, or make fail its next write */
	private static final class Member extends EmbeddedChannel {
		private volatile boolean gone;
		private volatile RuntimeException failure;

		@Override
		public boolean isActive() {
			return !gone && super.isActive();
		}

		@Override
		public ChannelFuture writeAndFlush(final Object message) {
			final RuntimeException failed = failure;

			if (failed != null) {
				failure = null;
				throw failed;
			}

			return super.writeAndFlush(message);
		}
	}

	/* Picks the given place of whatever list it is asked about, the last place when the list is shorter, and notes
	   how long each of the lists was */
	private static final class Pick extends Random {
		private final int place;
		private final List<Integer> lengths = new ArrayList<>();

		Pick(final int place) {
			this.place = place;
		}

		@Override
		public int nextInt(final int bound) {
			lengths.add(bound);
			return Math.min(place, bound - 1);
		}
	}

	private static void post(final ChainFleet fleet, final String member, final int writer, final List<Entry> entries) {
		fleet.host(member).receive(fleet.client(member, writer), ChainEntry.builder().entries(entries).build());
		fleet.deliver();
	}

	private static List<Entry> write(final ChainFleet fleet, final int writer, final int count) {
		return ChainFleet.entries(ChainFleet.key(writer), fleet.chain(), count, ChainFleet.NOW);
	}

	private static List<Entry> writeAt(final ChainFleet fleet, final int writer, final long... timestamps) {
		final FeedWriter feed = new FeedWriter(ChainFleet.key(writer), fleet.chain());

		return Arrays.stream(timestamps).mapToObj(timestamp -> feed.write(timestamp, ChainFleet.TAG,
			ChainFleet.padded(new byte[] { 1 }, ProxySettings.DEFAULTS.chainLimits().maxEntryBytes()))).toList();
	}

	/* Entries that take the given number of bytes in a frame, one after the other in the feed of a writer. Each size
	   must be one of the classes of the chain, which padding leaves as it is. */
	private static List<Entry> writeSized(final ChainFleet fleet, final int writer, final int... encodedBytes) {
		final FeedWriter feed = new FeedWriter(ChainFleet.key(writer), fleet.chain());
		final int maxEntryBytes = fleet.settings.get().orElseThrow().chainLimits().maxEntryBytes();

		return Arrays.stream(encodedBytes).mapToObj(bytes -> feed.write(ChainFleet.NOW, ChainFleet.TAG,
			ChainFleet.padded(new byte[bytes - Integer.BYTES - Entry.OVERHEAD], maxEntryBytes))).toList();
	}

	private static int[] repeated(final int encodedBytes, final int count) {
		return IntStream.generate(() -> encodedBytes).limit(count).toArray();
	}

	private static void store(final ChainFleet fleet, final List<Entry> entries) {
		entries.forEach(entry -> assertThat(fleet.host("gn-0").post(entry), is(Admission.ACCEPTED)));
	}

	private static FetchEntries.Range range(final int writer, final long from, final long to) {
		return new FetchEntries.Range(ChainFleet.writer(writer), from, to);
	}

	private static FetchEntries asking(final ChainId chain, final FetchEntries.Range... ranges) {
		return FetchEntries.builder().chainId(chain).ranges(List.of(ranges)).build();
	}

	private static ChainHeads claiming(final ChainId chain, final Map<WriterId, Head> heads) {
		return ChainHeads.builder().chainId(chain).heads(heads).build();
	}

	private static List<Entry> fetched(final ChainFleet fleet, final FetchEntries.Range... ranges) {
		return fleet.host("gn-0").fetch(fleet.stream("gn-0", "gn-1"), asking(fleet.chain(), ranges));
	}

	private static List<FetchEntries.Range> asked(final ChainFleet fleet, final Map<WriterId, Head> claimed) {
		return fleet.host("gn-1").reconcile(fleet.stream("gn-1", "gn-0"), claiming(fleet.chain(), claimed))
			.orElseThrow().getRanges();
	}

	/* A stream may send only so many heads in a second, so a test that asks many times asks once a second */
	private static List<FetchEntries.Range> askedInASecondOfItsOwn(final ChainFleet fleet,
		final Map<WriterId, Head> claimed) {

		fleet.clock.addAndGet(1000);
		return asked(fleet, claimed);
	}

	private static List<WriterId> writersSorted(final int from, final int count) {
		return IntStream.range(from, from + count).mapToObj(ChainFleet::writer).sorted().toList();
	}

	/* Heads at the last sequence number, which any request must clamp without overflowing */
	private static Map<WriterId, Head> aheadOf(final List<WriterId> writers) {
		return writers.stream().collect(Collectors.toMap(writer -> writer, writer -> new Head(Long.MAX_VALUE, 7)));
	}

	private static List<FetchEntries.Range> firstPackets(final List<WriterId> writers, final int... positions) {
		return Arrays.stream(positions).mapToObj(position -> new FetchEntries.Range(writers.get(position), 0,
			PACKET - 1)).toList();
	}

	private static List<Entry> sorted(final Stream<List<Entry>> feeds) {
		return feeds.flatMap(List::stream).sorted(BY_WRITER_THEN_SEQ).toList();
	}

	private static ChainHost hostHolding(final ChainFleet fleet, final Random picks, final Channel... members) {
		final Map<String, Channel> byMember = new HashMap<>();

		IntStream.range(0, members.length).forEach(number -> byMember.put("gn-" + (number + 1), members[number]));

		final ChainHost host = fleet.hostOver("gn-0", member -> Optional.ofNullable(byMember.get(member)), picks);

		host.refresh();
		return host;
	}

	private static void join(final ChainHost host, final Channel client, final int writer) {
		final JoinReply reply = host.join(client, JoinChain.builder().requestId(writer)
			.writer(ChainFleet.writer(writer)).chainId(host.hostedChains().get(0)).build());

		assertThat(reply.getStatus(), is(JoinReply.Status.JOINED));
	}

	private static void hold(final ChainHost host, final int chain, final int count) {
		ChainFleet.entries(ChainFleet.key(1), host.hostedChains().get(chain), count, ChainFleet.NOW)
			.forEach(host::post);
	}

	private static ChainHeads standing(final ChainHost host, final int chain) {
		final Chain held = host.chain(host.hostedChains().get(chain)).orElseThrow();

		return ChainHeads.builder().chainId(held.id()).heads(held.heads()).build();
	}

	private static void silence(final EmbeddedChannel... streams) {
		Arrays.stream(streams).forEach(stream -> stream.outboundMessages().clear());
	}

	private static void silence(final List<EmbeddedChannel> streams) {
		streams.forEach(stream -> stream.outboundMessages().clear());
	}

	private static List<ChainHeads> headsSentTo(final EmbeddedChannel stream) {
		return stream.outboundMessages().stream().filter(ChainHeads.class::isInstance).map(ChainHeads.class::cast)
			.toList();
	}

	private static int roundsToAgree(final ChainFleet fleet) {
		int rounds = 0;

		while (!fleet.agree(fleet.chain()) && rounds < ROUNDS) {
			fleet.round();
			rounds++;
		}

		return rounds;
	}

	private static void assertHoldEveryEntryOnce(final ChainFleet fleet, final List<Entry> expected) {
		IntStream.range(0, ChainFleet.MEMBERS).forEach(member -> assertThat(
			fleet.host("gn-" + member).chain(fleet.chain()).orElseThrow().entries(), equalTo(expected)));
	}

	/* Hosts that agree only tell each other where they stand */
	private static void assertNothingMoreIsSaid(final ChainFleet fleet) {
		fleet.deliveries.clear();
		fleet.round();
		fleet.round();

		assertThat(fleet.deliveries, is(not(empty())));
		assertThat(fleet.deliveries.stream().map(ChainFleet.Delivery::packet).toList(),
			everyItem(instanceOf(ChainHeads.class)));
	}

	private static ChainHeads headsOf(final ChainFleet fleet, final String member) {
		return ChainHeads.builder().chainId(fleet.chain()).heads(fleet.host(member).chain(fleet.chain())
			.orElseThrow().heads()).build();
	}

	@Example
	public void asksForTheEntriesAfterItsOwnHeadsAtMostOnePacketsWorthAtATime() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> first = write(fleet, 1, 100);
		final List<Entry> second = write(fleet, 2, 3);

		fleet.offline("gn-1");
		fleet.refreshAll();
		post(fleet, "gn-0", 1, first);
		post(fleet, "gn-0", 2, second);
		fleet.host("gn-1").receive(fleet.stream("gn-1", "gn-0"),
			ChainEntry.builder().entries(first.subList(0, 10)).build());

		final FetchEntries fetch = fleet.host("gn-1").reconcile(fleet.stream("gn-1", "gn-0"),
			headsOf(fleet, "gn-0")).orElseThrow();

		assertThat(fetch.getChainId(), equalTo(fleet.chain()));
		assertThat(fetch.getRanges(), containsInAnyOrder(new FetchEntries.Range(ChainFleet.writer(1), 10, 73),
			new FetchEntries.Range(ChainFleet.writer(2), 0, 2)));
	}

	@Example
	public void asksForNothingWhenItIsLevelOrAheadOrDoesNotHostTheChain() {
		final ChainFleet fleet = ChainFleet.group();

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		final ChainHeads level = headsOf(fleet, "gn-1");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");

		assertThat(fleet.host("gn-0").reconcile(member, level), is(Optional.empty()));
		assertThat(fleet.host("gn-0").reconcile(member, ChainHeads.builder().chainId(fleet.chain()).build()),
			is(Optional.empty()));
		assertThat(fleet.host("gn-0").reconcile(member, ChainHeads.builder().chainId(new ChainId(
			new byte[ChainId.SIZE])).heads(level.getHeads()).build()), is(Optional.empty()));
	}

	@Example
	public void servesTheRetainedEntriesOfTheRangesAskedFor() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 20);

		post(fleet, "gn-0", 1, entries);

		final List<Entry> served = fleet.host("gn-1").fetch(fleet.stream("gn-1", "gn-0"), FetchEntries.builder()
			.chainId(fleet.chain()).ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 5, 9),
				new FetchEntries.Range(ChainFleet.writer(7), 0, 9))).build());

		assertThat(served, equalTo(entries.subList(5, 10)));
	}

	@Example
	public void boundsWhatOneFetchCanDraw() {
		final ChainFleet fleet = ChainFleet.group();
		final Chain chain = fleet.host("gn-0").chain(fleet.chain()).orElseThrow();
		final List<FetchEntries.Range> everything = IntStream.range(0, 10).mapToObj(writer ->
			new FetchEntries.Range(ChainFleet.writer(writer), 0, Long.MAX_VALUE)).toList();

		IntStream.range(0, 10).forEach(writer -> write(fleet, writer, 70).forEach(chain::offer));

		final List<Entry> served = fleet.host("gn-0").fetch(fleet.stream("gn-0", "gn-1"), FetchEntries.builder()
			.chainId(chain.id()).ranges(everything).build());

		assertThat(served.size(), is(8 * 64));
	}

	@Example
	public void servesNothingForAChainItDoesNotHost() {
		final ChainFleet fleet = ChainFleet.group();

		post(fleet, "gn-0", 1, write(fleet, 1, 3));

		assertThat(fleet.host("gn-1").fetch(fleet.stream("gn-1", "gn-0"), FetchEntries.builder()
			.chainId(new ChainId(new byte[ChainId.SIZE])).ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1),
				0, 2))).build()), is(empty()));
	}

	@Example
	public void answersAMemberButNotAClientThatIsAttached() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 5);
		final ChainHeads ahead = ChainHeads.builder().chainId(fleet.chain())
			.heads(Map.of(ChainFleet.writer(1), new Head(8, 1))).build();
		final FetchEntries range = FetchEntries.builder().chainId(fleet.chain())
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 4))).build();
		final EmbeddedChannel client = fleet.client("gn-1", 9);
		final EmbeddedChannel member = fleet.stream("gn-1", "gn-0");

		post(fleet, "gn-0", 1, entries);

		assertThat(fleet.host("gn-1").reconcile(client, ahead), is(Optional.empty()));
		assertThat(fleet.host("gn-1").fetch(client, range), is(empty()));
		assertThat(fleet.host("gn-1").reconcile(member, ahead).isPresent(), is(true));
		assertThat(fleet.host("gn-1").fetch(member, range), equalTo(entries));
	}

	@Example
	public void answersNothingToAStreamThatIsNeitherAMemberNorAttached() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel stranger = new EmbeddedChannel();

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		assertThat(fleet.host("gn-1").reconcile(stranger, ChainHeads.builder().chainId(fleet.chain())
			.heads(Map.of(ChainFleet.writer(2), new Head(8, 1))).build()), is(Optional.empty()));
		assertThat(fleet.host("gn-1").fetch(stranger, FetchEntries.builder().chainId(fleet.chain())
			.ranges(List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 4))).build()), is(empty()));
	}

	@Example
	public void neverAnchorsAFeedAtAHeadAPeerNames() {
		final ChainFleet fleet = ChainFleet.group();
		final WriterId stranger = ChainFleet.writer(9);
		final Chain chain = fleet.host("gn-1").chain(fleet.chain()).orElseThrow();
		final List<Entry> own = write(fleet, 9, 2);
		final ChainHeads lying = ChainHeads.builder().chainId(fleet.chain()).heads(Map.of(stranger, new Head(1000, 7)))
			.build();

		final Optional<FetchEntries> fetch = fleet.host("gn-1").reconcile(fleet.stream("gn-1", "gn-0"), lying);

		assertThat(fetch.orElseThrow().getRanges(), contains(new FetchEntries.Range(stranger, 0, 63)));
		assertThat(chain.heads().containsKey(stranger), is(false));

		fleet.host("gn-1").receive(fleet.stream("gn-1", "gn-0"), ChainEntry.builder().entries(own).build());
		assertThat(chain.entries(), equalTo(own));
	}

	@Example
	public void asksForTheFirstEntriesOfAHeadAtTheLastSequenceNumber() {
		final ChainFleet fleet = ChainFleet.group();
		final WriterId stranger = ChainFleet.writer(9);
		final ChainHeads last = ChainHeads.builder().chainId(fleet.chain())
			.heads(Map.of(stranger, new Head(Long.MAX_VALUE, 7))).build();

		final Optional<FetchEntries> fetch = fleet.host("gn-1").reconcile(fleet.stream("gn-1", "gn-0"), last);

		assertThat(fetch.orElseThrow().getRanges(), contains(new FetchEntries.Range(stranger, 0, 63)));
	}

	@Example
	public void tellsOneRandomMemberWhereEachChainWithEntriesStands() {
		final ChainFleet fleet = ChainFleet.group();
		final List<ChainId> chains = fleet.host("gn-0").hostedChains();

		for (ChainId chainId : chains.subList(0, 2)) {
			fleet.host("gn-0").receive(fleet.stream("gn-0", "gn-1"), ChainEntry.builder()
				.entries(ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW)).build());
		}

		fleet.deliveries.clear();
		fleet.host("gn-0").reconcileRound();
		fleet.deliver();

		final List<ChainFleet.Delivery> announced = fleet.deliveries.stream()
			.filter(delivery -> delivery.packet() instanceof ChainHeads).toList();

		assertThat(announced.size(), is(2));
		announced.forEach(delivery -> assertThat(delivery.from(), is("gn-0")));
		announced.forEach(delivery -> assertThat(delivery.to().equals("gn-0"), is(false)));
		assertThat(announced.stream().map(delivery -> ((ChainHeads) delivery.packet()).getChainId())
			.collect(Collectors.toSet()), equalTo(Map.of(chains.get(0), 1, chains.get(1), 1).keySet()));
	}

	@Property(tries = 20)
	public void bringsMembersThatMissedEntriesLevelWithTheOthers(@ForAll long seed,
		@ForAll @IntRange(min = 1, max = 150) int backlog) {

		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> first = write(fleet, 1, backlog);
		final List<Entry> second = write(fleet, 2, 5);

		fleet.random.setSeed(seed);
		fleet.offline("gn-3");
		fleet.offline("gn-2");
		fleet.refreshAll();
		post(fleet, "gn-0", 1, first);
		post(fleet, "gn-1", 2, second);

		fleet.online("gn-3");
		fleet.online("gn-2");
		fleet.refreshAll();

		assertThat(fleet.agree(fleet.chain()), is(false));
		assertThat(roundsToAgree(fleet), lessThanOrEqualTo(ROUNDS - 1));
		assertThat(fleet.host("gn-3").chain(fleet.chain()).orElseThrow().entries().size(), is(backlog + 5));
		assertHoldEveryEntryOnce(fleet, sorted(Stream.of(first, second)));
		assertNothingMoreIsSaid(fleet);
	}

	@Property(tries = 20)
	public void bringsAMemberThatJoinsLateLevelWithTheOthers(@ForAll long seed) {
		final ChainFleet fleet = new ChainFleet(ChainFleet.MEMBERS);

		fleet.settings.set(Optional.of(ChainFleet.settings(ChainFleet.MEMBERS, 4, 16, 30, 3)));
		fleet.random.setSeed(seed);
		IntStream.range(0, ChainFleet.MEMBERS - 1).forEach(member -> fleet.host("gn-" + member));
		fleet.refreshAll();

		final List<Entry> first = write(fleet, 1, 12);
		final List<Entry> second = write(fleet, 2, 12);

		post(fleet, "gn-0", 1, first);
		post(fleet, "gn-2", 2, second);

		fleet.host("gn-3");
		fleet.refreshAll();

		assertThat(roundsToAgree(fleet), lessThanOrEqualTo(ROUNDS - 1));
		assertThat(fleet.host("gn-3").chain(fleet.chain()).orElseThrow().entries().size(), is(24));
		assertHoldEveryEntryOnce(fleet, sorted(Stream.of(first, second)));
		assertNothingMoreIsSaid(fleet);
	}

	@Example
	public void bringsAMemberLevelWhenTheStreamsToItTookNothingAtTheTime() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 20);
		final List<EmbeddedChannel> full = Stream.of("gn-0", "gn-2", "gn-3")
			.map(owner -> ChainFleet.unwritable(fleet.stream(owner, "gn-1"))).toList();

		post(fleet, "gn-0", 1, entries);

		assertThat(fleet.host("gn-1").chain(fleet.chain()).orElseThrow().entries(), is(empty()));

		full.forEach(EmbeddedChannel::flush);

		assertThat(roundsToAgree(fleet), lessThanOrEqualTo(ROUNDS - 1));
		assertHoldEveryEntryOnce(fleet, entries);
	}

	@Example
	public void answersNothingToNoStream() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 5);
		final ChainHeads ahead = claiming(fleet.chain(), Map.of(ChainFleet.writer(1), new Head(8, 1)));
		final FetchEntries wanted = asking(fleet.chain(), range(1, 0, 4));
		final EmbeddedChannel member = fleet.stream("gn-1", "gn-0");

		post(fleet, "gn-0", 1, entries);

		assertThat(fleet.host("gn-1").reconcile(null, ahead), is(Optional.empty()));
		assertThat(fleet.host("gn-1").fetch(null, wanted), is(empty()));
		assertThat(fleet.host("gn-1").reconcile(member, ahead).isPresent(), is(true));
		assertThat(fleet.host("gn-1").fetch(member, wanted), equalTo(entries));
	}

	@Example
	public void trustsTheMemberStreamsItHoldsAndNothingElse() {
		final HostedChain hosted = new HostedChain(new Chain(new ChainId(new byte[ChainId.SIZE]),
			ProxySettings.DEFAULTS.chainLimits(), () -> ChainFleet.NOW), new Epoch(0));
		final EmbeddedChannel member = new EmbeddedChannel();

		assertThat(hosted.trusts(null), is(false));
		assertThat(hosted.trusts(member), is(false));

		hosted.setMemberStreams(List.of(member));

		assertThat(hosted.trusts(member), is(true));
		assertThat(hosted.trusts(new EmbeddedChannel()), is(false));
		assertThat(hosted.trusts(null), is(false));
	}

	@Example
	public void stopsAnsweringAMemberItHoldsNoStreamToAnyMore() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 5);
		final ChainHeads ahead = claiming(fleet.chain(), Map.of(ChainFleet.writer(1), new Head(8, 1)));
		final FetchEntries wanted = asking(fleet.chain(), range(1, 0, 4));
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");

		post(fleet, "gn-0", 1, entries);

		assertThat(fleet.host("gn-0").reconcile(member, ahead).isPresent(), is(true));
		assertThat(fleet.host("gn-0").fetch(member, wanted), equalTo(entries));

		fleet.offline("gn-1");
		fleet.refreshAll();

		assertThat(fleet.host("gn-0").reconcile(member, ahead), is(Optional.empty()));
		assertThat(fleet.host("gn-0").fetch(member, wanted), is(empty()));
	}

	@Example
	public void answersNothingForAChainItDoesNotHostWhateverTheMemberClaims() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId unhosted = new ChainId(new byte[ChainId.SIZE]);
		final Map<WriterId, Head> ahead = Map.of(ChainFleet.writer(1), new Head(8, 1));
		final EmbeddedChannel member = fleet.stream("gn-1", "gn-0");

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		assertThat(fleet.host("gn-1").reconcile(member, claiming(unhosted, ahead)), is(Optional.empty()));
		assertThat(fleet.host("gn-1").fetch(member, asking(unhosted, range(1, 0, 4))), is(empty()));
		assertThat(fleet.host("gn-1").reconcile(member, claiming(fleet.chain(), ahead)).isPresent(), is(true));
	}

	@Example
	public void adoptsNothingFromTheHeadsOfAPeer() {
		final ChainFleet fleet = ChainFleet.group();
		final Chain chain = fleet.host("gn-1").chain(fleet.chain()).orElseThrow();

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		final Map<WriterId, Head> heads = chain.heads();
		final List<Entry> entries = chain.entries();
		final long bytes = chain.bytes();

		assertThat(asked(fleet, Map.of(ChainFleet.writer(1), new Head(100, 7), ChainFleet.writer(2), new Head(50, 7),
			ChainFleet.writer(3), new Head(0, 7))), hasSize(3));

		assertThat(chain.heads(), equalTo(heads));
		assertThat(chain.entries(), equalTo(entries));
		assertThat(chain.bytes(), is(bytes));
		assertThat(chain.heldBytes(), is(0L));
	}

	@Example
	public void asksForAtMostOnePacketsWorthOfEntriesOfAWriter() {
		final ChainFleet fleet = ChainFleet.group();
		final WriterId known = ChainFleet.writer(1);
		final WriterId unknown = ChainFleet.writer(2);

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		assertThat(asked(fleet, Map.of(unknown, new Head(0, 7))), contains(new FetchEntries.Range(unknown, 0, 0)));
		assertThat(asked(fleet, Map.of(unknown, new Head(63, 7))), contains(new FetchEntries.Range(unknown, 0, 63)));
		assertThat(asked(fleet, Map.of(unknown, new Head(64, 7))), contains(new FetchEntries.Range(unknown, 0, 63)));
		assertThat(asked(fleet, Map.of(unknown, new Head(100_000, 7))), contains(new FetchEntries.Range(unknown, 0, 63)));
		assertThat(asked(fleet, Map.of(known, new Head(5, 7))), contains(new FetchEntries.Range(known, 5, 5)));
		assertThat(asked(fleet, Map.of(known, new Head(68, 7))), contains(new FetchEntries.Range(known, 5, 68)));
		assertThat(asked(fleet, Map.of(known, new Head(69, 7))), contains(new FetchEntries.Range(known, 5, 68)));
	}

	@Example
	public void asksForEveryWriterThePeerIsAheadOfWhenThereAreEightAtMost() {
		for (final int count : new int[] { 1, 8 }) {
			final Pick picks = new Pick(0);
			final EmbeddedChannel member = new EmbeddedChannel();
			final ChainHost host = hostHolding(ChainFleet.group(), picks, member);
			final List<WriterId> ahead = writersSorted(100, count);

			assertThat(host.reconcile(member, claiming(host.hostedChains().get(0), aheadOf(ahead))).orElseThrow()
				.getRanges(), equalTo(firstPackets(ahead, IntStream.range(0, count).toArray())));
			assertThat(picks.lengths, is(empty()));
		}
	}

	/* The writers the peer is ahead of run in ascending order of id. With more than eight, the eight asked for
	   start at the drawn place of that list and carry on from its start when the end is passed. */
	@Example
	public void asksForEightWritersFromTheDrawnPlaceOnAndWrapsAround() {
		final List<WriterId> ten = writersSorted(100, 10);

		assertAskedFor(ten, 0, 0, 1, 2, 3, 4, 5, 6, 7);
		assertAskedFor(ten, 3, 3, 4, 5, 6, 7, 8, 9, 0);
		assertAskedFor(ten, 7, 7, 8, 9, 0, 1, 2, 3, 4);
		assertAskedFor(ten, Integer.MAX_VALUE, 9, 0, 1, 2, 3, 4, 5, 6);
		assertAskedFor(writersSorted(100, 9), Integer.MAX_VALUE, 8, 0, 1, 2, 3, 4, 5, 6);
	}

	private static void assertAskedFor(final List<WriterId> ahead, final int place, final int... positions) {
		final Pick picks = new Pick(place);
		final EmbeddedChannel member = new EmbeddedChannel();
		final ChainHost host = hostHolding(ChainFleet.group(), picks, member);

		assertThat(host.reconcile(member, claiming(host.hostedChains().get(0), aheadOf(ahead))).orElseThrow()
			.getRanges(), equalTo(firstPackets(ahead, positions)));
		assertThat(picks.lengths, equalTo(List.of(ahead.size())));
	}

	@Example
	public void drawsNoPlaceWhenThePeerIsNotAhead() {
		final Pick picks = new Pick(0);
		final EmbeddedChannel member = new EmbeddedChannel();
		final ChainHost host = hostHolding(ChainFleet.group(), picks, member);
		final ChainId chain = host.hostedChains().get(0);

		hold(host, 0, 3);

		assertThat(host.reconcile(member, claiming(chain, Map.of(ChainFleet.writer(1), new Head(2, 7)))),
			is(Optional.empty()));
		assertThat(host.reconcile(member, claiming(chain, Map.of(ChainFleet.writer(1), new Head(1, 7)))),
			is(Optional.empty()));
		assertThat(host.reconcile(member, claiming(chain, Map.of())), is(Optional.empty()));
		assertThat(picks.lengths, is(empty()));
	}

	@Example
	public void asksForAWriterAfterAnyNumberOfOnesNobodyCanServe() {
		assertAskedWithin(10, 20);
		assertAskedWithin(101, 300);
	}

	/* Writers that sort first and whose entries are gone everywhere are claimed by the peer in every round, and the
	   one that sorts last is the only one that can be filled. Eight places of the list are asked for from a drawn
	   start, so in a round that writer is among them with probability 8/n and is missed in all of them with
	   probability (1 - 8/n)^rounds: about 1e-14 for the first bound and 2e-11 for the second, for any seed (the
	   seed here is fixed as well). */
	private static void assertAskedWithin(final int ahead, final int rounds) {
		final ChainFleet fleet = ChainFleet.group();
		final List<WriterId> writers = writersSorted(100, ahead);
		final Map<WriterId, Head> claimed = aheadOf(writers);
		final List<List<FetchEntries.Range>> requests = IntStream.range(0, rounds)
			.mapToObj(round -> askedInASecondOfItsOwn(fleet, claimed)).toList();

		assertThat(requests.stream().anyMatch(request -> request.stream()
			.anyMatch(range -> range.writer().equals(writers.get(ahead - 1)))), is(true));
		requests.forEach(request -> assertThat(request.size(), is(8)));
		requests.forEach(request -> request.forEach(range ->
			assertThat(range, equalTo(new FetchEntries.Range(range.writer(), 0, 63)))));
	}

	@Example
	public void asksForNoWriterTheChainCannotTake() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-1");
		final EmbeddedChannel member = fleet.stream("gn-1", "gn-0");
		final WriterId known = ChainFleet.writer(0);
		final WriterId unknown = ChainFleet.writer(HostedChain.MAX_WRITERS);

		IntStream.range(0, HostedChain.MAX_WRITERS).forEach(writer -> assertThat(host.post(ChainFleet.entries(
			ChainFleet.key(writer), fleet.chain(), 1, ChainFleet.NOW).get(0)), is(Admission.ACCEPTED)));

		assertThat(host.post(ChainFleet.entries(ChainFleet.key(HostedChain.MAX_WRITERS), fleet.chain(), 1,
			ChainFleet.NOW).get(0)), is(Admission.FULL));
		assertThat(host.reconcile(member, claiming(fleet.chain(), Map.of(unknown, new Head(5, 7)))),
			is(Optional.empty()));
		assertThat(host.reconcile(member, claiming(fleet.chain(), Map.of(unknown, new Head(5, 7), known,
			new Head(3, 7)))).orElseThrow().getRanges(), contains(new FetchEntries.Range(known, 1, 3)));
	}

	@Example
	public void asksWithoutOverflowForAHeadAtTheLastSequenceNumberOfAKnownWriter() {
		final ChainFleet fleet = ChainFleet.group();
		final WriterId known = ChainFleet.writer(1);

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		assertThat(asked(fleet, Map.of(known, new Head(Long.MAX_VALUE, 7))),
			contains(new FetchEntries.Range(known, 5, 68)));
	}

	@Example
	public void asksOnlyForTheWritersThePeerIsAheadOf() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel member = fleet.stream("gn-1", "gn-0");

		post(fleet, "gn-0", 1, write(fleet, 1, 5));

		assertThat(asked(fleet, Map.of(ChainFleet.writer(1), new Head(2, 7), ChainFleet.writer(2), new Head(3, 7))),
			contains(new FetchEntries.Range(ChainFleet.writer(2), 0, 3)));
		assertThat(fleet.host("gn-1").reconcile(member, claiming(fleet.chain(),
			Map.of(ChainFleet.writer(1), new Head(4, 7)))), is(Optional.empty()));
		assertThat(fleet.host("gn-1").reconcile(member, claiming(fleet.chain(),
			Map.of(ChainFleet.writer(1), new Head(3, 7)))), is(Optional.empty()));
	}

	@Example
	public void answersOldestFirstWhateverTheOrderOfTheRanges() {
		final ChainFleet fleet = ChainFleet.group();
		final long start = ChainFleet.NOW;
		final List<Entry> first = writeAt(fleet, 1, start, start + 2000, start + 4000);
		final List<Entry> second = writeAt(fleet, 2, start + 1000, start + 3000);

		store(fleet, first);
		store(fleet, second);

		assertThat(fetched(fleet, range(2, 0, 1), range(1, 0, 2)),
			contains(first.get(0), second.get(0), first.get(1), second.get(1), first.get(2)));
	}

	@Example
	public void answersEntriesOfOneTimeByWriterAndThenBySequenceNumber() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> first = write(fleet, 1, 3);
		final List<Entry> second = write(fleet, 2, 3);

		store(fleet, first);
		store(fleet, second);

		assertThat(fetched(fleet, range(2, 0, 2), range(1, 0, 2)), equalTo(sorted(Stream.of(first, second))));
		assertThat(fetched(fleet, range(1, 2, 2), range(1, 0, 1)), equalTo(first));
	}

	@Example
	public void answersTheFirstPacketsWorthOfARangeAndNoMore() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 100);

		store(fleet, entries);

		assertThat(fetched(fleet, range(1, 0, Long.MAX_VALUE)), equalTo(entries.subList(0, 64)));
		assertThat(fetched(fleet, range(1, 10, 200)), equalTo(entries.subList(10, 74)));
		assertThat(fetched(fleet, range(1, 0, 63)), equalTo(entries.subList(0, 64)));
		assertThat(fetched(fleet, range(1, 0, 64)), equalTo(entries.subList(0, 64)));
		assertThat(fetched(fleet, range(1, 63, 63)), equalTo(entries.subList(63, 64)));
		assertThat(fetched(fleet, range(1, 99, Long.MAX_VALUE)), equalTo(entries.subList(99, 100)));
		assertThat(fetched(fleet, range(1, 100, Long.MAX_VALUE)), is(empty()));
		assertThat(fetched(fleet, range(1, Long.MAX_VALUE, Long.MAX_VALUE)), is(empty()));
		assertThat(fetched(fleet, range(1, Long.MAX_VALUE - 3, Long.MAX_VALUE)), is(empty()));
	}

	@Example
	public void answersTheFirstEightRangesOfARequestAndNoMore() {
		final ChainFleet fleet = ChainFleet.group();
		final List<List<Entry>> feeds = IntStream.range(0, 10).mapToObj(writer -> write(fleet, writer, 3)).toList();
		final FetchEntries.Range[] everyWriter = IntStream.range(0, 10).mapToObj(writer -> range(writer, 0, 2))
			.toArray(FetchEntries.Range[]::new);

		feeds.forEach(feed -> store(fleet, feed));

		assertThat(fetched(fleet, everyWriter), equalTo(sorted(feeds.stream().limit(8))));
	}

	@Example
	public void answersARangeAskedTwiceOnce() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 20);

		store(fleet, entries);

		assertThat(fetched(fleet, range(1, 0, 9), range(1, 0, 9), range(1, 5, 14)), equalTo(entries.subList(0, 15)));
	}

	@Example
	public void answersAFetchOnlyToAStreamThatTakesWrites() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = write(fleet, 1, 5);
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");

		store(fleet, entries);
		ChainFleet.unwritable(member);

		assertThat(fetched(fleet, range(1, 0, 4)), is(empty()));

		member.flush();

		assertThat(fetched(fleet, range(1, 0, 4)), equalTo(entries));
	}

	@Example
	public void answersAFetchOnlyToAStreamThatIsOpen() {
		final ChainFleet fleet = ChainFleet.group();
		final Member member = new Member();
		final ChainHost host = hostHolding(fleet, new Pick(0), member);
		final FetchEntries wanted = asking(host.hostedChains().get(0), new FetchEntries.Range(ChainFleet.writer(1), 0, 2));

		hold(host, 0, 3);

		assertThat(host.fetch(member, wanted), hasSize(3));

		member.gone = true;

		assertThat(host.fetch(member, wanted), is(empty()));
	}

	@Example
	public void cannotBeAskedForARangeThatRunsBackwardsOrBelowTheFirstEntry() {
		final WriterId writer = ChainFleet.writer(1);

		Expect.assertThrows(IllegalArgumentException.class, () -> new FetchEntries.Range(writer, -1, 5));
		Expect.assertThrows(IllegalArgumentException.class, () -> new FetchEntries.Range(writer, Long.MIN_VALUE, 5));
		Expect.assertThrows(IllegalArgumentException.class, () -> new FetchEntries.Range(writer, 5, 4));
		Expect.assertThrows(IllegalArgumentException.class, () -> new FetchEntries.Range(writer, Long.MAX_VALUE, 0));
		Expect.assertThrows(NullPointerException.class, () -> new FetchEntries.Range(null, 0, 1));
	}

	@Example
	public void answersNothingOfWritersItNeverSawOrEntriesTrimmedAway() {
		final ChainFleet fleet = ChainFleet.group();
		final long start = ChainFleet.NOW;
		final long later = start + 20_000;
		final List<Entry> entries = writeAt(fleet, 1, start, start, start, start, start, later, later, later, later, later);

		store(fleet, entries);
		fleet.clock.set(start + 31_000);
		fleet.host("gn-0").chain(fleet.chain()).orElseThrow().trim();

		assertThat(fetched(fleet, range(1, 0, 9)), equalTo(entries.subList(5, 10)));
		assertThat(fetched(fleet, range(1, 0, 4)), is(empty()));
		assertThat(fetched(fleet, range(2, 0, 9)), is(empty()));
	}

	@Example
	public void answersAFrameFullOfEntriesAndNotAByteMore() {
		final int whole = FRAME / (8 * PACKET);

		assertThat(whole * 8 * PACKET, is(FRAME));
		assertFrameHolds(whole, 8 * PACKET);
		assertFrameHolds(whole + 1, 8 * PACKET - 1);
		assertFrameHolds(whole - 1, 8 * PACKET);
	}

	/* The entries are of the largest class of their chain, which is what lets their size be chosen to the byte */
	private static void assertFrameHolds(final int encodedBytes, final int served) {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16, 32L * MEBIBYTE,
			30, encodedBytes - Integer.BYTES, 300, 3));
		final List<List<Entry>> feeds = IntStream.range(0, 8)
			.mapToObj(writer -> writeSized(fleet, writer, repeated(encodedBytes, PACKET))).toList();
		final FetchEntries.Range[] everyWriter = IntStream.range(0, 8).mapToObj(writer -> range(writer, 0, PACKET - 1))
			.toArray(FetchEntries.Range[]::new);

		feeds.forEach(feed -> store(fleet, feed));

		assertThat(ChainCodecs.encodedBytes(feeds.get(0).get(0)), is(encodedBytes));
		assertThat(fetched(fleet, everyWriter), equalTo(sorted(feeds.stream()).subList(0, served)));
	}

	@Example
	public void stopsAtTheEntryThatWouldPassTheFrameEvenWhenASmallerOneFollows() {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16,
			32L * MEBIBYTE, 30, MEBIBYTE, 300, 3));
		final List<Entry> entries = writeSized(fleet, 1, IntStream.concat(IntStream.of(repeated(MEBIBYTE
			+ Integer.BYTES, 8)), IntStream.of(1024 + Integer.BYTES)).toArray());

		store(fleet, entries);

		assertThat(fetched(fleet, range(1, 0, 8)), equalTo(entries.subList(0, 7)));
	}

	@Example
	public void answersSevenEntriesOfTheLargestSizeAndNoMore() {
		final ChainFleet fleet = ChainFleet.group(new ProxySettings(ChainFleet.MEMBERS, 3600, 4, 16,
			32L * MEBIBYTE, 30, MEBIBYTE, 300, 3));
		final List<Entry> entries = writeSized(fleet, 1, repeated(MEBIBYTE + Integer.BYTES, 9));

		store(fleet, entries);

		assertThat(entries.get(0).size(), is(MEBIBYTE));
		assertThat(fetched(fleet, range(1, 0, 8)), equalTo(entries.subList(0, 7)));
	}

	@Example
	public void tellsTheMemberAtThePlaceChosenAndNeverAClient() {
		IntStream.of(0, 1, 2, Integer.MAX_VALUE).forEach(ChainHostReconcileTest::assertTellsOnlyTheMemberAt);
	}

	private static void assertTellsOnlyTheMemberAt(final int place) {
		final ChainFleet fleet = ChainFleet.group();
		final Pick picks = new Pick(place);
		final List<EmbeddedChannel> streams = IntStream.range(0, 4).mapToObj(number -> new EmbeddedChannel()).toList();
		final ChainHost host = hostHolding(fleet, picks, streams.get(0), streams.get(1), streams.get(2));

		join(host, streams.get(3), 9);
		hold(host, 0, 3);
		silence(streams);
		host.reconcileRound();

		final Channel chosen = host.hosted(host.hostedChains().get(0)).orElseThrow().getMemberStreams()
			.get(Math.min(place, 2));

		streams.subList(0, 3).forEach(stream -> assertThat(headsSentTo(stream),
			equalTo(stream == chosen ? List.of(standing(host, 0)) : List.of())));
		assertThat(headsSentTo(streams.get(3)), is(empty()));
		assertThat(picks.lengths, equalTo(List.of(3)));
	}

	@Example
	public void choosesOnlyAmongMembersThatAreOpenAndTakeWrites() {
		IntStream.of(0, Integer.MAX_VALUE).forEach(ChainHostReconcileTest::assertTellsOnlyTheOpenMember);
	}

	private static void assertTellsOnlyTheOpenMember(final int place) {
		final ChainFleet fleet = ChainFleet.group();
		final Pick picks = new Pick(place);
		final Member gone = new Member();
		final EmbeddedChannel full = new EmbeddedChannel();
		final EmbeddedChannel open = new EmbeddedChannel();
		final ChainHost host = hostHolding(fleet, picks, gone, full, open);

		hold(host, 0, 3);
		silence(gone, full, open);
		gone.gone = true;
		ChainFleet.unwritable(full);
		host.reconcileRound();

		assertThat(headsSentTo(open), equalTo(List.of(standing(host, 0))));
		assertThat(headsSentTo(gone), is(empty()));
		assertThat(headsSentTo(full), is(empty()));
		assertThat(picks.lengths, equalTo(List.of(1)));
	}

	@Example
	public void tellsNobodyWhenNoMemberIsOpenAndTakesWrites() {
		final ChainFleet fleet = ChainFleet.group();
		final Pick picks = new Pick(0);
		final Member gone = new Member();
		final EmbeddedChannel full = new EmbeddedChannel();
		final ChainHost host = hostHolding(fleet, picks, gone, full);
		final ChainHost alone = hostHolding(fleet, picks);

		hold(host, 0, 3);
		hold(alone, 0, 3);
		silence(gone, full);
		gone.gone = true;
		ChainFleet.unwritable(full);
		host.reconcileRound();
		alone.reconcileRound();

		assertThat(headsSentTo(gone), is(empty()));
		assertThat(headsSentTo(full), is(empty()));
		assertThat(picks.lengths, is(empty()));
	}

	@Example
	public void tellsNothingForAChainWithoutEntries() {
		final ChainFleet fleet = ChainFleet.group();
		final Pick picks = new Pick(0);
		final List<EmbeddedChannel> members = IntStream.range(0, 3).mapToObj(number -> new EmbeddedChannel()).toList();
		final ChainHost host = hostHolding(fleet, picks, members.toArray(Channel[]::new));

		hold(host, 0, 3);
		hold(host, 2, 2);
		silence(members);
		host.reconcileRound();

		assertThat(members.stream().flatMap(member -> headsSentTo(member).stream()).toList(),
			equalTo(List.of(standing(host, 0), standing(host, 2))));
		assertThat(picks.lengths, equalTo(List.of(3, 3)));
	}

	@Example
	public void tellsNothingWhenNoChainHasEntries() {
		final ChainFleet fleet = ChainFleet.group();
		final Pick picks = new Pick(0);
		final List<EmbeddedChannel> members = IntStream.range(0, 3).mapToObj(number -> new EmbeddedChannel()).toList();

		hostHolding(fleet, picks, members.toArray(Channel[]::new)).reconcileRound();

		assertThat(members.stream().flatMap(member -> member.outboundMessages().stream()).toList(), is(empty()));
		assertThat(picks.lengths, is(empty()));
	}

	@Example
	public void spreadsTheAnnouncementsOverTheMembers() {
		final ChainFleet fleet = ChainFleet.group();
		final List<EmbeddedChannel> members = IntStream.range(0, 3).mapToObj(number -> new EmbeddedChannel()).toList();
		final ChainHost host = hostHolding(fleet, new Random(3), members.toArray(Channel[]::new));

		hold(host, 0, 3);
		silence(members);
		IntStream.range(0, ANNOUNCEMENTS).forEach(round -> host.reconcileRound());

		final List<Integer> received = members.stream().map(member -> headsSentTo(member).size()).toList();

		assertThat(received.stream().mapToInt(Integer::intValue).sum(), is(ANNOUNCEMENTS));
		assertThat(received, everyItem(greaterThanOrEqualTo(1)));
	}

	@Example
	public void carriesOnPastAStreamWhoseWriteFails() {
		final ChainFleet fleet = ChainFleet.group();
		final Member failing = new Member();
		final ChainHost host = hostHolding(fleet, new Pick(0), failing);

		hold(host, 0, 3);
		hold(host, 2, 2);
		silence(failing);
		failing.failure = new IllegalStateException(FAILURE_DETAIL);

		final List<ILoggingEvent> warnings = ChainFleet.warningsWhile(host::reconcileRound);

		assertThat(headsSentTo(failing), equalTo(List.of(standing(host, 2))));
		assertThat(warnings, hasSize(1));
		assertThat(warnings.get(0).getFormattedMessage(), containsString(IllegalStateException.class.getName()));
		assertThat(warnings.get(0).getFormattedMessage(), not(containsString(FAILURE_DETAIL)));
		assertThat(warnings.get(0).getThrowableProxy(), is(nullValue()));
	}

	@Example
	public void announcesAtMostOnePacketsWorthOfHeads() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel member = new EmbeddedChannel();
		final ChainHost host = hostHolding(fleet, new Pick(0), member);
		final Chain chain = host.chain(host.hostedChains().get(0)).orElseThrow();

		IntStream.rangeClosed(0, ChainCodecs.MAX_HEADS_PER_PACKET).forEach(writer -> chain.offer(
			ChainFleet.entries(ChainFleet.key(writer), chain.id(), 1, ChainFleet.NOW).get(0)));
		silence(member);
		host.reconcileRound();

		final List<ChainHeads> sent = headsSentTo(member);

		assertThat(chain.heads().size(), is(ChainCodecs.MAX_HEADS_PER_PACKET + 1));
		assertThat(sent, hasSize(1));
		assertThat(sent.get(0).getHeads().size(), is(ChainCodecs.MAX_HEADS_PER_PACKET));
		assertThat(chain.heads().entrySet().containsAll(sent.get(0).getHeads().entrySet()), is(true));
	}
}
