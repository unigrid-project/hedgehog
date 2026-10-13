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

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainHeads;
import org.unigrid.hedgehog.model.network.packet.FetchEntries;
import org.unigrid.hedgehog.model.network.packet.JoinChain;
import org.unigrid.hedgehog.model.network.packet.JoinReply;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryKind;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.chain.Head;

public class ChainHostBudgetTest {
	private static final long SECOND = 1000;
	private static final int TIMEOUT_SECONDS = 10;

	/* The limits written out, so that changing one of them is a change to this test as well */
	private static final int JOINS = 8;
	private static final int ENTRIES = 4096;
	private static final int HEADS = 16;
	private static final int RANGES = 64;
	private static final int FETCH_BYTES = 32 * 1024 * 1024;

	/* What one answer serves at most, and an answer of that many entries of that size fills a frame exactly */
	private static final int RANGES_PER_ANSWER = 8;
	private static final int ANSWER_ENTRIES = 512;
	private static final int FRAMED_BYTES = 16 * 1024;

	/* A stream that stops in its first check of whether it is open once it is armed, which a join makes while it
	   holds the join lock, for longer than another join is waited for */
	private static final class StallingStream extends EmbeddedChannel {
		private final CountDownLatch stalled = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private volatile boolean armed;

		@Override @SneakyThrows
		public boolean isActive() {
			if (armed) {
				armed = false;
				stalled.countDown();
				assertThat(released.await(3 * TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
			}

			return super.isActive();
		}

		void arm() {
			armed = true;
		}
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	/* Answers what the action returns on a thread of its own while another thread holds the monitor, so an action
	   that waits for the monitor fails the test instead of answering. The monitor is held for longer than the action
	   is waited for. */
	@SneakyThrows
	private static <T> T whileHeld(final Object monitor, final Callable<T> action) {
		final ExecutorService threads = Executors.newFixedThreadPool(2);
		final CountDownLatch held = new CountDownLatch(1);
		final CountDownLatch released = new CountDownLatch(1);

		try {
			threads.submit(() -> {
				synchronized (monitor) {
					held.countDown();
					return released.await(3 * TIMEOUT_SECONDS, TimeUnit.SECONDS);
				}
			});

			awaitIn(held);
			return threads.submit(action).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			released.countDown();
			threads.shutdownNow();
		}
	}

	private static ChainEntry packet(final List<Entry> entries) {
		return ChainEntry.builder().entries(entries).build();
	}

	private static ChainHeads ahead(final ChainFleet fleet) {
		return ChainHeads.builder().chainId(fleet.chain()).heads(Map.of(ChainFleet.writer(2), new Head(5, 1))).build();
	}

	private static FetchEntries fetching(final ChainFleet fleet, final List<FetchEntries.Range> ranges) {
		return FetchEntries.builder().chainId(fleet.chain()).ranges(ranges).build();
	}

	private static FetchEntries onlyOf(final ChainFleet fleet, final int writer) {
		return fetching(fleet, List.of(new FetchEntries.Range(ChainFleet.writer(writer), 0, 0)));
	}

	/* The first entries of writer 1 in ranges of a packet each, as many ranges as one fetch asks for */
	private static List<FetchEntries.Range> ranges(final int entries) {
		final int perRange = ANSWER_ENTRIES / RANGES_PER_ANSWER;

		return IntStream.range(0, RANGES_PER_ANSWER).mapToObj(range -> new FetchEntries.Range(ChainFleet.writer(1),
			(long) range * perRange, Math.min((long) range * perRange + perRange, entries) - 1)).toList();
	}

	/* Entries of one writer that take exactly the bytes given in a frame, the length before each included */
	private static List<Entry> framed(final ChainId chainId, final int writer, final int count, final int bytes) {
		final FeedWriter feed = new FeedWriter(ChainFleet.key(writer), chainId);
		final byte[] payload = new byte[bytes - Integer.BYTES - Entry.OVERHEAD];

		return IntStream.range(0, count).mapToObj(number -> feed.write(ChainFleet.NOW, Fingerprint.NONE,
			EntryKind.SEALED, payload)).toList();
	}

	private static JoinReply.Status join(final ChainFleet fleet, final EmbeddedChannel stream, final int writer) {
		return fleet.host("gn-0").join(stream, JoinChain.builder().requestId(1).writer(ChainFleet.writer(writer))
			.chainId(fleet.chain()).build()).getStatus();
	}

	private static JoinReply.Status join(final ChainFleet fleet, final EmbeddedChannel stream) {
		return join(fleet, stream, 1);
	}

	private static List<Entry> receive(final ChainFleet fleet, final List<Entry> entries) {
		fleet.host("gn-0").receive(fleet.stream("gn-0", "gn-1"), ChainEntry.builder().entries(entries).build());
		return fleet.host("gn-0").chain(fleet.chain()).orElseThrow().entries();
	}

	@Example
	public void answersTheJoinsOfAStreamOnlyUpToItsBudgetEachSecond() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel stream = new EmbeddedChannel();
		final List<JoinReply.Status> first = IntStream.range(0, StreamBudgets.Kind.JOINS.getPerSecond())
			.mapToObj(attempt -> join(fleet, stream)).toList();

		assertThat(first, everyItem(is(JoinReply.Status.JOINED)));
		assertThat(join(fleet, stream), is(JoinReply.Status.FULL));

		fleet.clock.addAndGet(SECOND);
		assertThat(join(fleet, stream), is(JoinReply.Status.JOINED));
	}

	@Example
	public void takesTheEntriesOfAStreamOnlyUpToItsBudgetEachSecond() {
		final ChainFleet fleet = ChainFleet.group();
		final int budget = StreamBudgets.Kind.ENTRIES.getPerSecond();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), budget + 2, ChainFleet.NOW);

		assertThat(receive(fleet, entries.subList(0, budget + 1)), is(empty()));
		assertThat(receive(fleet, entries.subList(0, budget)).size(), is(budget));
		assertThat(receive(fleet, entries.subList(budget, budget + 1)).size(), is(budget));

		fleet.clock.addAndGet(SECOND);
		assertThat(receive(fleet, entries.subList(budget, budget + 1)).size(), is(budget + 1));
	}

	@Example
	public void answersTheHeadsOfAStreamOnlyUpToItsBudgetEachSecond() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHeads ahead = ChainHeads.builder().chainId(fleet.chain())
			.heads(Map.of(ChainFleet.writer(2), new Head(5, 1))).build();
		final List<Boolean> first = IntStream.range(0, StreamBudgets.Kind.HEADS.getPerSecond())
			.mapToObj(attempt -> fleet.host("gn-0").reconcile(fleet.stream("gn-0", "gn-1"), ahead).isPresent()).toList();

		assertThat(first, everyItem(is(true)));
		assertThat(fleet.host("gn-0").reconcile(fleet.stream("gn-0", "gn-1"), ahead).isPresent(), is(false));

		fleet.clock.addAndGet(SECOND);
		assertThat(fleet.host("gn-0").reconcile(fleet.stream("gn-0", "gn-1"), ahead).isPresent(), is(true));
	}

	@Example
	public void servesTheRangesOfAStreamOnlyUpToItsBudgetEachSecond() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 5, ChainFleet.NOW);
		final FetchEntries request = FetchEntries.builder().chainId(fleet.chain()).ranges(IntStream.range(0, 8)
			.mapToObj(range -> new FetchEntries.Range(ChainFleet.writer(1), 0, 4)).toList()).build();
		final EmbeddedChannel stream = fleet.stream("gn-0", "gn-1");

		receive(fleet, entries);

		for (int served = 0; served < StreamBudgets.Kind.RANGES.getPerSecond() / 8; served++) {
			assertThat(fleet.host("gn-0").fetch(stream, request).isEmpty(), is(false));
		}

		assertThat(fleet.host("gn-0").fetch(stream, request), is(empty()));

		fleet.clock.addAndGet(SECOND);
		assertThat(fleet.host("gn-0").fetch(stream, request).isEmpty(), is(false));
	}

	@Example
	public void keepsTheBudgetsOfStreamsApart() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel greedy = new EmbeddedChannel();

		IntStream.range(0, StreamBudgets.Kind.JOINS.getPerSecond() + 1).forEach(attempt -> join(fleet, greedy));

		assertThat(join(fleet, greedy), is(JoinReply.Status.FULL));
		assertThat(join(fleet, new EmbeddedChannel(), 2), is(JoinReply.Status.JOINED));
		assertThat(receive(fleet, ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 1, ChainFleet.NOW)).size(),
			equalTo(1));
	}

	@Example
	public void answersTheEighthJoinOfAStreamInASecondAndRefusesTheNinth() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel stream = new EmbeddedChannel();

		IntStream.range(0, JOINS).forEach(attempt -> assertThat(join(fleet, stream), is(JoinReply.Status.JOINED)));
		assertThat(join(fleet, stream), is(JoinReply.Status.FULL));
	}

	@Example
	public void takesTheFourThousandAndNinetySixthEntryOfAStreamInASecondAndIgnoresTheNext() {
		final ChainFleet fleet = ChainFleet.group();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 3, ChainFleet.NOW);

		receive(fleet, Collections.nCopies(ENTRIES - 1, entries.get(0)));
		receive(fleet, entries.subList(1, 2));

		assertThat(receive(fleet, entries.subList(2, 3)), equalTo(entries.subList(0, 2)));
	}

	@Example
	public void answersTheSixteenthHeadsOfAStreamInASecondAndIgnoresTheNext() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final ChainHeads ahead = ahead(fleet);

		IntStream.range(0, HEADS).forEach(asked -> assertThat(host.reconcile(member, ahead).isPresent(), is(true)));
		assertThat(host.reconcile(member, ahead), is(Optional.empty()));
	}

	@Example
	public void servesTheSixtyFourthRangeOfAStreamInASecondAndNotTheNext() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW);
		final FetchEntries first = fetching(fleet, List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 0)));

		receive(fleet, entries);

		IntStream.range(0, RANGES).forEach(range -> assertThat(host.fetch(member, first), equalTo(entries)));
		assertThat(host.fetch(member, first), is(empty()));
	}

	@Example
	public void chargesAFetchOnlyForTheRangesOneAnswerServes() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW);
		final FetchEntries greedy = fetching(fleet, Collections.nCopies(RANGES + 1,
			new FetchEntries.Range(ChainFleet.writer(1), 0, 0)));

		receive(fleet, entries);

		IntStream.range(0, RANGES / RANGES_PER_ANSWER).forEach(fetch -> assertThat(host.fetch(member, greedy),
			equalTo(entries)));
		assertThat(host.fetch(member, greedy), is(empty()));
	}

	/* Entries that take a sixteenth of a mebibyte in a frame each, so that answers add up to the budget exactly: three
	   answers of 512 of them and one of 511 leave room for one more, which an entry a byte larger does not fit */
	@Example
	public void servesTheBytesOfAStreamUpToItsBudgetExactlyEachSecond() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final List<Entry> filler = framed(fleet.chain(), 1, ANSWER_ENTRIES, FRAMED_BYTES);
		final Entry tooLarge = framed(fleet.chain(), 2, 1, FRAMED_BYTES + 1).get(0);
		final Entry fitting = framed(fleet.chain(), 3, 1, FRAMED_BYTES).get(0);
		final FetchEntries whole = fetching(fleet, ranges(ANSWER_ENTRIES));
		final FetchEntries allButOne = fetching(fleet, ranges(ANSWER_ENTRIES - 1));

		receive(fleet, Stream.concat(filler.stream(), Stream.of(tooLarge, fitting)).toList());

		assertThat(ChainCodecs.encodedBytes(tooLarge), is(FRAMED_BYTES + 1));
		assertThat(ChainCodecs.encodedBytes(fitting), is(FRAMED_BYTES));
		IntStream.range(0, 3).forEach(answer -> assertThat(host.fetch(member, whole), equalTo(filler)));
		assertThat(host.fetch(member, allButOne), equalTo(filler.subList(0, ANSWER_ENTRIES - 1)));
		assertThat(4L * ANSWER_ENTRIES * FRAMED_BYTES, is((long) FETCH_BYTES));

		assertThat(host.fetch(member, onlyOf(fleet, 2)), is(empty()));
		assertThat(host.fetch(member, onlyOf(fleet, 3)), equalTo(List.of(fitting)));
		assertThat(host.fetch(member, onlyOf(fleet, 3)), is(empty()));

		fleet.clock.addAndGet(SECOND);
		assertThat(host.fetch(member, whole), equalTo(filler));
	}

	@Example
	public void chargesAStreamNothingForEntriesItMayNotWrite() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel client = fleet.client("gn-0", 1);
		final Entry foreign = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 1, ChainFleet.NOW).get(0);
		final List<Entry> own = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW);

		host.receive(client, packet(Collections.nCopies(ENTRIES, foreign)));
		host.receive(client, packet(own));

		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), equalTo(own));
	}

	@Example
	public void ignoresTheEntriesOfAStreamOverItsBudgetWithoutWaitingForTheChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final Chain chain = host.chain(fleet.chain()).orElseThrow();
		final List<Entry> entries = ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 2, ChainFleet.NOW);

		receive(fleet, Collections.nCopies(ENTRIES, entries.get(0)));

		assertThat(whileHeld(chain, () -> host.receive(member, packet(entries.subList(1, 2)))), is(empty()));
		assertThat(chain.entries(), equalTo(entries.subList(0, 1)));
	}

	@Example
	public void answersNoHeadsOfAStreamOverItsBudgetWithoutWaitingForTheChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final ChainHeads ahead = ahead(fleet);

		IntStream.range(0, HEADS).forEach(asked -> host.reconcile(member, ahead));

		assertThat(whileHeld(host.chain(fleet.chain()).orElseThrow(), () -> host.reconcile(member, ahead)),
			is(Optional.empty()));
	}

	@Example
	public void servesNoRangesToAStreamOverItsBudgetWithoutWaitingForTheChain() {
		final ChainFleet fleet = ChainFleet.group();
		final ChainHost host = fleet.host("gn-0");
		final EmbeddedChannel member = fleet.stream("gn-0", "gn-1");
		final FetchEntries first = fetching(fleet, List.of(new FetchEntries.Range(ChainFleet.writer(1), 0, 0)));

		receive(fleet, ChainFleet.entries(ChainFleet.key(1), fleet.chain(), 1, ChainFleet.NOW));
		IntStream.range(0, RANGES).forEach(range -> host.fetch(member, first));

		assertThat(whileHeld(host.chain(fleet.chain()).orElseThrow(), () -> host.fetch(member, first)), is(empty()));
	}

	@Example
	public void refusesAJoinOverTheBudgetWithoutWaitingForTheChain() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel stream = new EmbeddedChannel();

		IntStream.range(0, JOINS).forEach(attempt -> join(fleet, stream));

		assertThat(whileHeld(fleet.host("gn-0").chain(fleet.chain()).orElseThrow(), () -> join(fleet, stream)),
			is(JoinReply.Status.FULL));
	}

	@Example @SneakyThrows
	public void refusesAJoinOverTheBudgetWithoutWaitingForAnotherJoin() {
		final ChainFleet fleet = ChainFleet.group();
		final EmbeddedChannel greedy = new EmbeddedChannel();
		final StallingStream stalling = new StallingStream();
		final ExecutorService threads = Executors.newFixedThreadPool(2);

		IntStream.range(0, JOINS).forEach(attempt -> join(fleet, greedy));
		stalling.arm();

		try {
			final Future<JoinReply.Status> stalled = threads.submit(() -> join(fleet, stalling, 2));

			awaitIn(stalling.stalled);
			assertThat(threads.submit(() -> join(fleet, greedy)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
				is(JoinReply.Status.FULL));

			stalling.released.countDown();
			assertThat(stalled.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(JoinReply.Status.JOINED));
		} finally {
			stalling.released.countDown();
			threads.shutdownNow();
		}
	}
}