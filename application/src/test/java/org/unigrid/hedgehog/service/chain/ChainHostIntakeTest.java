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

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.model.network.packet.LeaveChain;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.ChainLimits;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryCodec;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.InvalidEntryException;
import org.unigrid.hedgehog.proxy.chain.Offer;
import org.unigrid.hedgehog.proxy.chain.WriterId;
import org.unigrid.hedgehog.proxy.group.Epoch;
import org.unigrid.hedgehog.proxy.group.ProxySettings;

public class ChainHostIntakeTest {
	private static final int MAX_ENTRY_BYTES = 16 * 1024;
	private static final int TIMEOUT_SECONDS = 20;
	private static final int THREADS = 16;

	/* A writer counted just after the lock is released leaves a window of a few instructions, which only some
	   rounds of streams offering at once fall into */
	private static final int ROUNDS = 500;
	private static final long BEYOND_RETENTION_MILLIS = 31_000;
	private static final long EPOCH_MILLIS = 3_600_000;
	private static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);

	private static ChainId chainOf(final ChainFleet fleet, final int index) {
		return fleet.host("gn-0").hostedChains().get(index);
	}

	private static Channel member(final ChainFleet fleet) {
		return fleet.stream("gn-0", "gn-1");
	}

	private static List<EntryRefused> receive(final ChainFleet fleet, final Channel stream, final List<Entry> entries) {
		return fleet.host("gn-0").receive(stream, ChainEntry.builder().entries(entries).build());
	}

	private static List<Entry> stored(final ChainFleet fleet, final ChainId chainId) {
		return fleet.host("gn-0").chain(chainId).orElseThrow().entries();
	}

	private static Comparator<Entry> fifo() {
		return Comparator.comparingLong((Entry entry) -> entry.header().timestamp())
			.thenComparing(entry -> entry.header().writer()).thenComparingLong(entry -> entry.header().seq());
	}

	private static EmbeddedChannel attach(final ChainFleet fleet, final int writer, final ChainId chainId) {
		final EmbeddedChannel client = new EmbeddedChannel();
		final JoinReply reply = fleet.host("gn-0").join(client, JoinChain.builder().requestId(1)
			.writer(ChainFleet.writer(writer)).chainId(chainId).build());

		assertThat(reply.getStatus(), is(JoinReply.Status.JOINED));
		return client;
	}

	/* Turns the epoch of the first member the given number of times and puts the clock back, so that what was
	   written in the first epoch is still within the retention window */
	private static void retire(final ChainFleet fleet, final int turns) {
		IntStream.range(0, turns).forEach(turn -> {
			fleet.clock.addAndGet(EPOCH_MILLIS);
			fleet.host("gn-0").refresh();
		});

		fleet.clock.set(ChainFleet.NOW);
	}

	private static Entry first(final ChainId chainId, final int writer) {
		return ChainFleet.entries(ChainFleet.key(writer), chainId, 1, ChainFleet.NOW).get(0);
	}

	private static Entry ofKind(final ChainId chainId, final EntryKind kind, final int writer) {
		return new FeedWriter(ChainFleet.key(writer), chainId).write(ChainFleet.NOW, Fingerprint.NONE, kind,
			ChainFleet.padded(new byte[] { 1 }, MAX_ENTRY_BYTES));
	}

	private static HostedChain hostedChain(final int maxWriters, final ChainLimits limits, final LongSupplier clock) {
		return new HostedChain(new Chain(CHAIN, limits, clock), new Epoch(0), maxWriters);
	}

	private static HostedChain hostedChain(final int maxWriters) {
		return hostedChain(maxWriters, ProxySettings.DEFAULTS.chainLimits(), () -> ChainFleet.NOW);
	}

	private static Optional<Admission> admission(final Optional<Offer> offer) {
		return offer.map(Offer::admission);
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	private static void awaitBlocked(final AtomicReference<Thread> thread, final Future<?> meanwhile) {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (!meanwhile.isDone() && (thread.get() == null || thread.get().getState() != Thread.State.BLOCKED)) {
			assertThat(System.nanoTime() < deadline, is(true));
			Thread.yield();
		}
	}

	@SneakyThrows
	private static long taken(final List<Future<Optional<Offer>>> offers) {
		long taken = 0;

		for (final Future<Optional<Offer>> offer : offers) {
			taken += offer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isPresent() ? 1 : 0;
		}

		return taken;
	}

	@Example
	public void appendsTheEntriesOfAHostedChainFromAMember() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 1);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 5, ChainFleet.NOW);

		assertThat(receive(fleet, member(fleet), entries), is(empty()));
		assertThat(stored(fleet, chainId), equalTo(entries));
	}

	@Example
	public void takesNothingFromAStreamThatIsNone() {
		final HostedChain bare = hostedChain(HostedChain.MAX_WRITERS);
		final WriterId stranger = ChainFleet.writer(7);

		assertThat(bare.accepts(null, stranger), is(false));

		bare.setMemberStreams(List.of(new EmbeddedChannel()));
		assertThat(bare.accepts(null, stranger), is(false));
	}

	@Example
	public void ignoresEntriesItAlreadyHas() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW);

		receive(fleet, member(fleet), entries);

		assertThat(receive(fleet, member(fleet), entries), is(empty()));
		assertThat(stored(fleet, chainId), equalTo(entries));
	}

	@Example
	public void dropsEntriesOfChainsItDoesNotHost() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId foreign = new ChainId(new byte[ChainId.SIZE]);

		assertThat(receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(1), foreign, 3, ChainFleet.NOW)),
			is(empty()));
		fleet.host("gn-0").hostedChains().forEach(id -> assertThat(stored(fleet, id), is(empty())));
	}

	@Example
	public void keepsTakingTheEntriesOfAChainOfAnEarlierEpoch() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId earlier = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), earlier, 3, ChainFleet.NOW);

		fleet.clock.addAndGet(EPOCH_MILLIS);
		fleet.host("gn-0").refresh();
		fleet.clock.set(ChainFleet.NOW);

		assertThat(receive(fleet, member(fleet), entries), is(empty()));
		assertThat(stored(fleet, earlier), equalTo(entries));
	}

	@Example
	public void keepsPostingToAChainOfAnEarlierEpoch() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId earlier = chainOf(fleet, 0);
		final Entry entry = first(earlier, 1);

		retire(fleet, 1);

		assertThat(fleet.host("gn-0").post(entry), is(Admission.ACCEPTED));
		assertThat(stored(fleet, earlier), contains(entry));
	}

	@Example
	public void keepsServingTheClientsOfAChainThatRetired() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId earlier = chainOf(fleet, 0);
		final EmbeddedChannel client = attach(fleet, 1, earlier);
		final List<Entry> own = ChainFleet.entries(ChainFleet.key(1), earlier, 2, ChainFleet.NOW);
		final List<Entry> others = ChainFleet.entries(ChainFleet.key(2), earlier, 2, ChainFleet.NOW);

		retire(fleet, 1);

		assertThat(receive(fleet, client, own), is(empty()));
		assertThat(receive(fleet, member(fleet), others), is(empty()));
		assertThat(stored(fleet, earlier).size(), is(4));
		assertThat(((ChainEntry) client.readOutbound()).getEntries(), equalTo(others));
	}

	@Example
	public void takesTheEntriesOfAChainThatIsFourEpochsBehind() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId earlier = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), earlier, 3, ChainFleet.NOW);

		retire(fleet, 4);

		assertThat(receive(fleet, member(fleet), entries), is(empty()));
		assertThat(stored(fleet, earlier), equalTo(entries));
	}

	@Example
	public void dropsTheEntriesOfAChainOnceItsRetirementHasEnded() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId earlier = chainOf(fleet, 0);

		retire(fleet, 5);

		assertThat(fleet.host("gn-0").chain(earlier), equalTo(Optional.empty()));
		assertThat(receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(1), earlier, 3, ChainFleet.NOW)),
			is(empty()));
		assertThat(fleet.host("gn-0").post(first(earlier, 1)), is(Admission.WRONG_CHAIN));
		assertThat(fleet.host("gn-0").chain(earlier), equalTo(Optional.empty()));
	}

	@Example
	public void dropsEverythingWhileTheProxyIsOff() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);

		fleet.settings.set(Optional.empty());
		fleet.host("gn-0").refresh();

		assertThat(receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW)),
			is(empty()));
	}

	@Example
	public void appendsEntriesAheadOfAGapOnceTheMissingOneArrives() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 5, ChainFleet.NOW);

		for (int seq = 4; seq > 0; seq--) {
			assertThat(receive(fleet, member(fleet), List.of(entries.get(seq))), is(empty()));
		}

		assertThat(stored(fleet, chainId), is(empty()));
		receive(fleet, member(fleet), List.of(entries.get(0)));
		assertThat(stored(fleet, chainId), equalTo(entries));
	}

	@Example
	public void tellsTheWriterOfASealedEntryTheChainHasNoRoomFor() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 6, ChainFleet.NOW);

		assertThat(receive(fleet, member(fleet), entries), contains(EntryRefused.of(entries.get(5), Admission.FULL)));
		assertThat(stored(fleet, chainId), equalTo(entries.subList(0, 5)));
	}

	@Example
	public void holdsOnlyAsMuchOutOfOrderTrafficAsTheBufferOfTheChainTakes() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.smallChains(20));
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 11, ChainFleet.NOW);
		final Chain chain = fleet.host("gn-0").chain(chainId).orElseThrow();

		assertThat(receive(fleet, member(fleet), entries.subList(1, 11)), contains(entries.subList(6, 11).stream()
			.map(entry -> EntryRefused.of(entry, Admission.FULL)).toArray(EntryRefused[]::new)));
		assertThat(chain.heldBytes(), is(5L * entries.get(1).size()));
		assertThat(chain.entries(), is(empty()));
	}

	@Example
	public void saysNothingOfEntriesItDropsForOtherReasons() throws InvalidEntryException {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final Entry genuine = ChainFleet.entries(ChainFleet.key(1), chainId, 1, ChainFleet.NOW).get(0);
		final byte[] forged = EntryCodec.encode(genuine);
		final Entry expired = ChainFleet.entries(ChainFleet.key(2), chainId, 1, ChainFleet.NOW - 60_000).get(0);
		final Entry oversized = new FeedWriter(ChainFleet.key(3), chainId).write(ChainFleet.NOW, Fingerprint.NONE,
			EntryKind.SEALED, new byte[MAX_ENTRY_BYTES]);

		forged[forged.length - 1] ^= 1;

		assertThat(receive(fleet, member(fleet), List.of(EntryCodec.decode(forged, MAX_ENTRY_BYTES), expired,
			oversized)), is(empty()));
		assertThat(stored(fleet, chainId), is(empty()));
	}

	@Property(tries = 30)
	public void mergesFeedsThatArriveInAnyOrderIntoOneFifo(@ForAll Random random,
		@ForAll @IntRange(min = 1, max = 4) int writers, @ForAll @IntRange(min = 1, max = 6) int perWriter) {

		final ChainFleet fleet = ChainFleet.group();
		final Chain chain = fleet.host("gn-0").chain(chainOf(fleet, 0)).orElseThrow();
		final List<Entry> arrival = new ArrayList<>(IntStream.range(0, writers).boxed().flatMap(writer ->
			ChainFleet.entries(ChainFleet.key(writer), chain.id(), perWriter, ChainFleet.NOW + writer * 1000L).stream())
			.toList());
		final List<Entry> sorted = arrival.stream().sorted(fifo()).toList();

		Collections.shuffle(arrival, random);

		assertThat(receive(fleet, member(fleet), arrival), is(empty()));
		assertThat(chain.entries(), equalTo(sorted));
	}

	@Example
	public void dropsEntriesFromAStreamThatIsNeitherAMemberNorAttached() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), chainId, 6, ChainFleet.NOW);

		assertThat(receive(fleet, new EmbeddedChannel(), entries), is(empty()));
		assertThat(stored(fleet, chainId), is(empty()));
	}

	@Example
	public void takesTheEntriesOfAClientOnlyFromTheStreamItJoinedThrough() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final EmbeddedChannel client = new EmbeddedChannel();
		final List<Entry> own = ChainFleet.entries(ChainFleet.key(1), chainId, 2, ChainFleet.NOW);
		final List<Entry> another = ChainFleet.entries(ChainFleet.key(2), chainId, 2, ChainFleet.NOW);

		fleet.host("gn-0").join(client, JoinChain.builder().requestId(1).writer(ChainFleet.writer(1))
			.chainId(chainId).build());

		receive(fleet, new EmbeddedChannel(), own);
		receive(fleet, client, another);
		assertThat(stored(fleet, chainId), is(empty()));

		receive(fleet, client, own);
		assertThat(stored(fleet, chainId), equalTo(own));
	}

	private static List<List<Entry>> fillWithTheMostWritersItTakes(final ChainFleet fleet, final ChainId chainId) {
		final List<List<Entry>> feeds = IntStream.rangeClosed(0, HostedChain.MAX_WRITERS).mapToObj(writer ->
			ChainFleet.entries(ChainFleet.key(writer), chainId, 2, ChainFleet.NOW)).toList();

		feeds.subList(0, HostedChain.MAX_WRITERS).forEach(feed -> receive(fleet, member(fleet), feed.subList(0, 1)));
		fleet.clock.addAndGet(1000);
		return feeds;
	}

	@Example
	public void holdsNoMoreWritersThanAPacketOfHeadsCarries() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final List<List<Entry>> feeds = fillWithTheMostWritersItTakes(fleet, chainId);
		final List<Entry> late = feeds.get(HostedChain.MAX_WRITERS);

		receive(fleet, member(fleet), late);
		receive(fleet, member(fleet), feeds.get(0).subList(1, 2));

		final Chain chain = fleet.host("gn-0").chain(chainId).orElseThrow();

		assertThat(chain.heads().size(), is(HostedChain.MAX_WRITERS));
		assertThat(chain.heads().containsKey(late.get(0).header().writer()), is(false));
		assertThat(chain.heads().get(ChainFleet.writer(0)).seq(), is(1L));
		assertThat(chain.entries(), not(hasItem(late.get(0))));
	}

	@Example
	public void sendsNewClientsToAChainThatStillTakesWriters() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId exhausted = chainOf(fleet, 0);
		final JoinChain anywhere = JoinChain.builder().requestId(1).writer(ChainFleet.writer(HostedChain.MAX_WRITERS))
			.build();

		fillWithTheMostWritersItTakes(fleet, exhausted);

		final JoinReply elsewhere = fleet.host("gn-0").join(new EmbeddedChannel(), anywhere);
		final JoinReply named = fleet.host("gn-0").join(new EmbeddedChannel(), JoinChain.builder().requestId(2)
			.writer(ChainFleet.writer(HostedChain.MAX_WRITERS)).chainId(exhausted).build());

		assertThat(elsewhere.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(elsewhere.getChainId(), is(not(Optional.of(exhausted))));
		assertThat(named.getStatus(), is(JoinReply.Status.FULL));
	}

	@Example @SneakyThrows
	public void dropsEntriesOfAStreamThatMayNotWriteThemBeforeTheChainLooksAtThem() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final Chain chain = fleet.host("gn-0").chain(chainId).orElseThrow();
		final EmbeddedChannel client = attach(fleet, 1, chainId);
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(2), chainId, 1, ChainFleet.NOW);
		final ExecutorService streams = Executors.newSingleThreadExecutor();

		try {
			/* A call that reaches the chain waits for this lock, so one that answers has not looked at the entry */
			synchronized (chain) {
				for (final Channel stream : List.of(new EmbeddedChannel(), client)) {
					assertThat(streams.submit(() -> receive(fleet, stream, entries)).get(TIMEOUT_SECONDS,
						TimeUnit.SECONDS), is(empty()));
				}
			}
		} finally {
			streams.shutdownNow();
		}

		receive(fleet, member(fleet), entries);
		assertThat(stored(fleet, chainId), equalTo(entries));
	}

	@Example
	public void relaysTheEntriesOfAnyWriterFromEveryMemberStream() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> relayed = new ArrayList<>();

		attach(fleet, 1, chainId);

		for (int member = 1; member < ChainFleet.MEMBERS; member++) {
			final List<Entry> entries = ChainFleet.entries(ChainFleet.key(member), chainId, 2, ChainFleet.NOW);

			assertThat(receive(fleet, fleet.stream("gn-0", "gn-" + member), entries), is(empty()));
			relayed.addAll(entries);
		}

		assertThat(stored(fleet, chainId), equalTo(relayed.stream().sorted(fifo()).toList()));
	}

	@Example
	public void takesFromAClientOnlyTheWriterAndTheChainItJoinedAs() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final ChainId another = chainOf(fleet, 1);
		final EmbeddedChannel client = attach(fleet, 1, chainId);
		final List<Entry> own = ChainFleet.entries(ChainFleet.key(1), chainId, 2, ChainFleet.NOW);
		final List<Entry> ofAnotherWriter = ChainFleet.entries(ChainFleet.key(2), chainId, 1, ChainFleet.NOW);
		final List<Entry> onAnotherChain = ChainFleet.entries(ChainFleet.key(1), another, 1, ChainFleet.NOW);

		assertThat(receive(fleet, client, List.of(ofAnotherWriter.get(0), own.get(0), onAnotherChain.get(0),
			own.get(1))), is(empty()));
		assertThat(stored(fleet, chainId), equalTo(own));
		assertThat(stored(fleet, another), is(empty()));
	}

	@Example
	public void saysNothingToAClientThatIsNoLongerAttached() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainId chainId = chainOf(fleet, 0);
		final EmbeddedChannel leaving = attach(fleet, 1, chainId);
		final EmbeddedChannel closing = attach(fleet, 2, chainId);
		final List<Entry> fromLeaving = ChainFleet.entries(ChainFleet.key(1), chainId, 1, ChainFleet.NOW);
		final List<Entry> fromClosing = ChainFleet.entries(ChainFleet.key(2), chainId, 1, ChainFleet.NOW);

		receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(9), chainId, 5, ChainFleet.NOW));

		assertThat(receive(fleet, leaving, fromLeaving), contains(EntryRefused.of(fromLeaving.get(0), Admission.FULL)));
		assertThat(receive(fleet, closing, fromClosing), contains(EntryRefused.of(fromClosing.get(0), Admission.FULL)));

		fleet.host("gn-0").leave(leaving, LeaveChain.builder().chainId(chainId).writer(ChainFleet.writer(1)).build());
		closing.close();

		assertThat(receive(fleet, leaving, fromLeaving), is(empty()));
		assertThat(receive(fleet, closing, fromClosing), is(empty()));
	}

	@Example
	public void refusesOnlySealedEntriesForWantOfRoom() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainId chainId = chainOf(fleet, 0);
		final Chain chain = fleet.host("gn-0").chain(chainId).orElseThrow();

		receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(9), chainId, 5, ChainFleet.NOW));

		for (final EntryKind kind : EntryKind.values()) {
			final Entry entry = ofKind(chainId, kind, 3 + kind.ordinal());
			final boolean isSealed = kind == EntryKind.SEALED;
			final List<EntryRefused> expected = isSealed ? List.of(EntryRefused.of(entry, Admission.FULL)) : List.of();

			assertThat(receive(fleet, member(fleet), List.of(entry)), equalTo(expected));
			assertThat(chain.heads().containsKey(entry.header().writer()), is(!isSealed));
		}
	}

	@Example
	public void namesTheEntryOfEveryRefusal() {
		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainId chainId = chainOf(fleet, 0);
		final ChainId another = chainOf(fleet, 1);
		final List<Entry> ofFirstWriter = ChainFleet.entries(ChainFleet.key(1), chainId, 2, ChainFleet.NOW);
		final Entry ofSecondWriter = first(another, 2);

		receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(9), chainId, 5, ChainFleet.NOW));
		receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(9), another, 5, ChainFleet.NOW));

		assertThat(receive(fleet, member(fleet), List.of(ofFirstWriter.get(0), ofSecondWriter, ofFirstWriter.get(1))),
			contains(refusal(chainId, 1, 0), refusal(another, 2, 0), refusal(chainId, 1, 1)));
	}

	private static EntryRefused refusal(final ChainId chainId, final int writer, final long seq) {
		return EntryRefused.builder().chainId(chainId).writer(ChainFleet.writer(writer)).seq(seq)
			.admission(Admission.FULL).build();
	}

	@Example
	public void saysNothingOfEntriesAFeedOrTheClockTurnsAway() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> feed = ChainFleet.entries(ChainFleet.key(1), chainId, 3, ChainFleet.NOW);
		final FeedWriter forker = new FeedWriter(ChainFleet.key(1), chainId);
		final List<Entry> fork = IntStream.range(0, 3).mapToObj(number -> forker.write(ChainFleet.NOW,
			Fingerprint.NONE, EntryKind.SEALED, ChainFleet.padded(new byte[] { 7 }, MAX_ENTRY_BYTES))).toList();
		final Entry future = ChainFleet.entries(ChainFleet.key(2), chainId, 1,
			ChainFleet.NOW + BEYOND_RETENTION_MILLIS).get(0);

		assertThat(receive(fleet, member(fleet), feed), is(empty()));
		assertThat(receive(fleet, member(fleet), List.of(feed.get(2), fork.get(0), fork.get(2), future)),
			is(empty()));
		assertThat(stored(fleet, chainId), equalTo(feed));
	}

	@Example
	public void answersAnEmptyListToAPacketWithoutEntries() {
		final ChainFleet fleet = ChainFleet.group();

		assertThat(fleet.host("gn-0").receive(member(fleet), new ChainEntry()), is(empty()));
		assertThat(fleet.host("gn-0").receive(member(fleet), ChainEntry.builder().build()), is(empty()));
		assertThat(receive(fleet, member(fleet), List.of()), is(empty()));
	}

	@Example
	public void dropsEntriesOfAChainOfAnEarlierEpoch() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId earlier = chainOf(fleet, 0);

		fleet.clock.addAndGet(TimeUnit.HOURS.toMillis(1));
		fleet.refreshAll();

		final ChainId current = chainOf(fleet, 0);

		assertThat(fleet.host("gn-0").hostedChains(), not(hasItem(earlier)));
		assertThat(receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(1), earlier, 2,
			fleet.clock.get())), is(empty()));
		fleet.host("gn-0").hostedChains().forEach(id -> assertThat(stored(fleet, id), is(empty())));

		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), current, 2, fleet.clock.get());

		receive(fleet, member(fleet), entries);
		assertThat(stored(fleet, current), equalTo(entries));
	}

	@Property(tries = 30)
	public void refusesNoMoreThanItReceivedAndOnlyForWhatTheStreamMayWrite(@ForAll Random random,
		@ForAll @IntRange(min = 0, max = 2) int kind, @ForAll @IntRange(min = 1, max = 12) int size) {

		final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
		final ChainId chainId = chainOf(fleet, 0);
		final List<Channel> streams = List.of(member(fleet), attach(fleet, 1, chainId), new EmbeddedChannel());
		final List<Entry> pool = new ArrayList<>(IntStream.rangeClosed(1, 3).boxed().flatMap(writer ->
			ChainFleet.entries(ChainFleet.key(writer), chainId, 4, ChainFleet.NOW).stream()).toList());

		receive(fleet, member(fleet), ChainFleet.entries(ChainFleet.key(9), chainId, 5, ChainFleet.NOW));
		Collections.shuffle(pool, random);

		final List<Entry> received = pool.subList(0, size);
		final List<EntryRefused> refusals = receive(fleet, streams.get(kind), received);
		final List<EntryRefused> mayBeRefused = received.stream().filter(entry -> kind == 0
			|| (kind == 1 && entry.header().writer().equals(ChainFleet.writer(1))))
			.map(entry -> EntryRefused.of(entry, Admission.FULL)).toList();

		assertThat(refusals.size(), lessThanOrEqualTo(received.size()));
		assertThat(mayBeRefused.containsAll(refusals), is(true));
	}

	private static List<Entry> firstEntriesOf(final ChainId chainId, final int writers) {
		return IntStream.range(0, writers).mapToObj(writer -> first(chainId, writer)).toList();
	}

	private static Entry second(final ChainId chainId, final int writer) {
		return ChainFleet.entries(ChainFleet.key(writer), chainId, 2, ChainFleet.NOW).get(1);
	}

	@Example
	public void takesTheLastWriterThatFitsAndNoWriterAfterIt() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final Chain chain = fleet.host("gn-0").chain(chainId).orElseThrow();
		final List<Entry> firsts = firstEntriesOf(chainId, HostedChain.MAX_WRITERS + 1);
		final WriterId last = ChainFleet.writer(HostedChain.MAX_WRITERS - 1);
		final WriterId beyond = ChainFleet.writer(HostedChain.MAX_WRITERS);

		assertThat(HostedChain.MAX_WRITERS, is(4096));
		receive(fleet, member(fleet), firsts.subList(0, HostedChain.MAX_WRITERS - 1));
		assertThat(chain.heads().size(), is(HostedChain.MAX_WRITERS - 1));

		receive(fleet, member(fleet), List.of(firsts.get(HostedChain.MAX_WRITERS - 1)));
		assertThat(chain.heads().size(), is(HostedChain.MAX_WRITERS));
		assertThat(chain.heads().containsKey(last), is(true));

		fleet.clock.addAndGet(1000);
		receive(fleet, member(fleet), List.of(firsts.get(HostedChain.MAX_WRITERS), second(chainId,
			HostedChain.MAX_WRITERS)));
		assertThat(chain.heads().size(), is(HostedChain.MAX_WRITERS));
		assertThat(chain.heads().containsKey(beyond), is(false));
		assertThat(chain.heldBytes(), is(0L));

		receive(fleet, member(fleet), List.of(second(chainId, HostedChain.MAX_WRITERS - 1), second(chainId, 0)));
		assertThat(chain.heads().get(last).seq(), is(1L));
		assertThat(chain.heads().get(ChainFleet.writer(0)).seq(), is(1L));
	}

	@Example
	public void attachesAWriterOnlyWhileTheChainTakesMore() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainId chainId = chainOf(fleet, 0);
		final List<Entry> firsts = firstEntriesOf(chainId, HostedChain.MAX_WRITERS);
		final EmbeddedChannel last = new EmbeddedChannel();

		receive(fleet, member(fleet), firsts.subList(0, HostedChain.MAX_WRITERS - 1));

		final JoinReply atTheLimit = fleet.host("gn-0").join(last, JoinChain.builder().requestId(1)
			.writer(ChainFleet.writer(HostedChain.MAX_WRITERS - 1)).build());

		assertThat(atTheLimit.getChainId(), is(Optional.of(chainId)));
		assertThat(receive(fleet, last, List.of(firsts.get(HostedChain.MAX_WRITERS - 1))), is(empty()));

		final JoinReply beyondIt = fleet.host("gn-0").join(new EmbeddedChannel(), JoinChain.builder().requestId(2)
			.writer(ChainFleet.writer(HostedChain.MAX_WRITERS)).chainId(chainId).build());
		final JoinReply known = fleet.host("gn-0").join(new EmbeddedChannel(), JoinChain.builder().requestId(3)
			.writer(ChainFleet.writer(0)).chainId(chainId).build());
		final JoinReply elsewhere = fleet.host("gn-0").join(new EmbeddedChannel(), JoinChain.builder().requestId(4)
			.writer(ChainFleet.writer(HostedChain.MAX_WRITERS)).build());

		assertThat(beyondIt.getStatus(), is(JoinReply.Status.FULL));
		assertThat(known.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(elsewhere.getStatus(), is(JoinReply.Status.JOINED));
		assertThat(elsewhere.getChainId(), is(not(Optional.of(chainId))));
	}

	@Example
	public void takesNoSlotForAWriterWhoseEntryTheChainTurnsAway() throws InvalidEntryException {
		final byte[] forged = EntryCodec.encode(first(CHAIN, 2));
		final byte[] anotherId = new byte[ChainId.SIZE];

		forged[forged.length - 1] ^= 1;
		anotherId[0] = 1;

		assertTakesNoSlot(Admission.NOT_AUTHENTIC, EntryCodec.decode(forged, MAX_ENTRY_BYTES));
		assertTakesNoSlot(Admission.EXPIRED, ChainFleet.entries(ChainFleet.key(2), CHAIN, 1,
			ChainFleet.NOW - BEYOND_RETENTION_MILLIS).get(0));
		assertTakesNoSlot(Admission.FUTURE, ChainFleet.entries(ChainFleet.key(2), CHAIN, 1,
			ChainFleet.NOW + BEYOND_RETENTION_MILLIS).get(0));
		assertTakesNoSlot(Admission.TOO_LARGE, new FeedWriter(ChainFleet.key(2), CHAIN).write(ChainFleet.NOW,
			Fingerprint.NONE, EntryKind.SEALED, new byte[MAX_ENTRY_BYTES]));
		assertTakesNoSlot(Admission.WRONG_CHAIN, first(new ChainId(anotherId), 2));
	}

	private static void assertTakesNoSlot(final Admission turnedAway, final Entry entry) {
		final HostedChain hosted = hostedChain(1);

		assertThat(admission(hosted.offer(entry)), is(Optional.of(turnedAway)));
		assertThat(admission(hosted.offer(first(CHAIN, 3))), is(Optional.of(Admission.ACCEPTED)));
	}

	@Example
	public void takesNoSlotForASealedEntryTheChainHasNoRoomFor() {
		final HostedChain hosted = hostedChain(2, ChainFleet.tinyChains().chainLimits(), () -> ChainFleet.NOW);

		ChainFleet.entries(ChainFleet.key(1), CHAIN, 5, ChainFleet.NOW).forEach(hosted::offer);

		assertThat(admission(hosted.offer(first(CHAIN, 2))), is(Optional.of(Admission.FULL)));
		assertThat(admission(hosted.offer(ofKind(CHAIN, EntryKind.ACCEPT, 3))), is(Optional.of(Admission.ACCEPTED)));
		assertThat(admission(hosted.offer(first(CHAIN, 4))), is(Optional.empty()));
	}

	@Example
	public void countsAWriterWhoseEntryWaitsForAGap() {
		final HostedChain hosted = hostedChain(2);
		final List<Entry> feed = ChainFleet.entries(ChainFleet.key(1), CHAIN, 2, ChainFleet.NOW);

		assertThat(admission(hosted.offer(feed.get(1))), is(Optional.of(Admission.AHEAD)));
		assertThat(admission(hosted.offer(first(CHAIN, 2))), is(Optional.of(Admission.ACCEPTED)));
		assertThat(admission(hosted.offer(first(CHAIN, 3))), is(Optional.empty()));
		assertThat(admission(hosted.offer(feed.get(0))), is(Optional.of(Admission.ACCEPTED)));
		assertThat(hosted.getChain().heads().get(ChainFleet.writer(1)).seq(), is(1L));
	}

	@Example
	public void attachesNoWriterTheChainTakesNoMoreOf() {
		final HostedChain hosted = hostedChain(1);
		final EmbeddedChannel stream = new EmbeddedChannel();

		hosted.offer(first(CHAIN, 1));

		assertThat(hosted.attach(ChainFleet.writer(2), stream, 16, true), is(HostedChain.Attachment.REFUSED));
		assertThat(hosted.isAttached(ChainFleet.writer(2)), is(false));
		assertThat(hosted.attach(ChainFleet.writer(1), stream, 16, true), is(HostedChain.Attachment.NEW));
	}

	@Example @SneakyThrows
	public void countsAWriterAndOffersItsEntryUnderOneLock() {
		final CountDownLatch insideTheChain = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean firstRead = new AtomicBoolean(true);
		final HostedChain hosted = hostedChain(1, ProxySettings.DEFAULTS.chainLimits(), () -> {
			if (firstRead.getAndSet(false)) {
				insideTheChain.countDown();
				awaitIn(release);
			}

			return ChainFleet.NOW;
		});
		final Entry holder = first(CHAIN, 1);
		final Entry rival = first(CHAIN, 2);
		final AtomicReference<Thread> rivalThread = new AtomicReference<>();
		final ExecutorService streams = Executors.newFixedThreadPool(2);

		try {
			final Future<Optional<Offer>> holding = streams.submit(() -> hosted.offer(holder));

			awaitIn(insideTheChain);

			final Future<Optional<Offer>> rivalling = streams.submit(() -> {
				rivalThread.set(Thread.currentThread());
				return hosted.offer(rival);
			});

			awaitBlocked(rivalThread, rivalling);
			release.countDown();

			assertThat(admission(holding.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)), is(Optional.of(Admission.ACCEPTED)));
			assertThat(rivalling.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(Optional.empty()));
			assertThat(hosted.getChain().heads().size(), is(1));
		} finally {
			release.countDown();
			streams.shutdownNow();
		}
	}

	@Example
	public void takesNoMoreWritersThanItMayWhenStreamsOfferAtOnce() {
		final int limit = 3;
		final List<Entry> entries = IntStream.range(0, THREADS).mapToObj(writer -> first(CHAIN, writer)).toList();
		final ExecutorService streams = Executors.newFixedThreadPool(THREADS);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final HostedChain hosted = hostedChain(limit);
				final CountDownLatch go = new CountDownLatch(1);
				final List<Future<Optional<Offer>>> offers = entries.stream().map(entry -> streams.submit(() -> {
					awaitIn(go);
					return hosted.offer(entry);
				})).toList();

				go.countDown();

				assertThat(taken(offers), is((long) limit));
				assertThat(hosted.getChain().heads().size(), is(limit));
			}
		} finally {
			streams.shutdownNow();
		}
	}
}
