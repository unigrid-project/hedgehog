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
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.slf4j.LoggerFactory;
import org.unigrid.hedgehog.jqwik.Expect;
import org.unigrid.hedgehog.model.network.packet.ChainEntry;
import org.unigrid.hedgehog.model.network.packet.ChainStem;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.Chain;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.FeedWriter;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public class ChainOutboxTest {
	private static final int EARLIER = 10_000;
	private static final int UNTIL_THE_CROWD_EXPIRES = 21_000;
	private static final int RETENTION_MILLIS = 30_000;
	private static final int WRITER = 1;
	private static final int TIMEOUT_SECONDS = 10;
	private static final int ROUNDS = 20;
	private static final String DETAIL = "10.1.2.3:4567";
	private static final Runnable NOTHING = () -> { };
	private static final ChainId CHAIN = new ChainId(new byte[ChainId.SIZE]);
	private static final List<Entry> FEED = ChainFleet.entries(ChainFleet.key(WRITER), CHAIN, 1000, ChainFleet.NOW);

	/* The answers that say the chain holds the entry or is past it, so the next entry of the feed may follow */
	private static final EnumSet<Admission> TAKEN = EnumSet.of(Admission.ACCEPTED, Admission.AHEAD, Admission.DUPLICATE,
		Admission.REPLAYED);

	/* The answers that end the outbox: every one that says the chain will never take the entry, and a chain that is
	   gone */
	private static final List<Admission> ENDING = List.of(Admission.BROKEN, Admission.TOO_LARGE, Admission.FUTURE,
		Admission.EXPIRED, Admission.NOT_AUTHENTIC, Admission.WRONG_CHAIN);

	private final ChainFleet fleet = ChainFleet.group(ChainFleet.tinyChains());
	private final ChainHost host = fleet.host("gn-1");
	private final List<Boolean> announced = new ArrayList<>();
	private final ChainOutbox outbox = new ChainOutbox(host, fleet.chain(), ChainFleet.writer(WRITER), announced::add);

	/* A host whose own replica answers as the test tells it and which keeps what was posted to it. It holds no chain,
	   so a resend only lifts the pause. A call made while the outbox holds its lock, or a post made while another
	   is on its way, fails the test. */
	private static final class ScriptedHost extends ChainHost {
		private final IntFunction<Admission> answers;
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger inFlight = new AtomicInteger();
		private final AtomicReference<Runnable> whilePosting = new AtomicReference<>(NOTHING);
		private final List<Entry> posted = new CopyOnWriteArrayList<>();
		private final List<Optional<Admission>> answered = new CopyOnWriteArrayList<>();
		private final List<Entry> placed = new CopyOnWriteArrayList<>();
		private volatile Object outbox = new Object();

		ScriptedHost(final IntFunction<Admission> answers) {
			super(Optional::empty, null, peer -> Optional.empty(), () -> ChainFleet.NOW, new Random(1),
				new ManualScheduler());

			this.answers = answers;
		}

		private void outsideTheLock() {
			assertThat(Thread.holdsLock(outbox), is(false));
		}

		@Override
		public Admission post(final Entry entry) {
			outsideTheLock();
			assertThat(inFlight.incrementAndGet(), is(1));

			try {
				posted.add(entry);
				whilePosting.getAndSet(NOTHING).run();

				final Admission admission = answer();

				if (admission != Admission.FULL) {
					placed.add(entry);
				}

				return admission;
			} finally {
				inFlight.decrementAndGet();
			}
		}

		private Admission answer() {
			try {
				final Admission admission = answers.apply(calls.getAndIncrement());

				answered.add(Optional.of(admission));
				return admission;
			} catch (IllegalStateException ex) {
				answered.add(Optional.empty());
				throw ex;
			}
		}

		@Override
		public WriterPauses pauses() {
			outsideTheLock();
			return super.pauses();
		}

		@Override
		public void resend(final ChainId chainId, final WriterId writer) {
			outsideTheLock();
			super.resend(chainId, writer);
		}

		boolean isPaused() {
			return super.pauses().isPaused(CHAIN, ChainFleet.writer(WRITER));
		}
	}

	/* An outbox over a scripted host, with what its listener was told and on which threads */
	private record Scripted(ScriptedHost host, ChainOutbox outbox, List<Boolean> told, List<Thread> tellers) {
		static Scripted answering(final IntFunction<Admission> answers) {
			final ScriptedHost host = new ScriptedHost(answers);
			final List<Boolean> told = new CopyOnWriteArrayList<>();
			final List<Thread> tellers = new CopyOnWriteArrayList<>();
			final AtomicReference<ChainOutbox> self = new AtomicReference<>();
			final ChainOutbox outbox = new ChainOutbox(host, CHAIN, ChainFleet.writer(WRITER), writable -> {
				assertThat(Thread.holdsLock(self.get()), is(false));
				told.add(writable);
				tellers.add(Thread.currentThread());
			});

			self.set(outbox);
			host.outbox = outbox;
			return new Scripted(host, outbox, told, tellers);
		}

		/* The given answers first, then the replica takes everything */
		static Scripted answering(final Admission... first) {
			return answering(call -> call < first.length ? first[call] : Admission.ACCEPTED);
		}

		void accept(final int from, final int to) {
			FEED.subList(from, to).forEach(outbox::accept);
		}

		List<Long> postedSeqs() {
			return host.posted.stream().map(entry -> entry.header().seq()).toList();
		}
	}

	private static IntFunction<Admission> throwingFirst() {
		return call -> {
			if (call == 0) {
				throw new IllegalStateException(DETAIL);
			}

			return Admission.ACCEPTED;
		};
	}

	/* Each post is of the entry posted before it again when that one found no room or the host failed, and of the
	   next entry of the feed otherwise, beginning with the first; nothing follows an answer that ends the outbox */
	private static void assertPostedInFeedOrder(final ScriptedHost host) {
		for (int post = 0; post < host.posted.size(); post++) {
			final boolean again = post > 0 && host.answered.get(post - 1).filter(admission ->
				admission != Admission.FULL).isEmpty();
			final long previous = post == 0 ? -1 : host.posted.get(post - 1).header().seq();

			assertThat(host.posted.get(post).header().seq(), is(again ? previous : previous + 1));
			assertThat(post > 0 && host.answered.get(post - 1).filter(ENDING::contains).isPresent(), is(false));
		}
	}

	private static boolean hasEnded(final ScriptedHost host) {
		return host.answered.stream().flatMap(Optional::stream).anyMatch(ENDING::contains);
	}

	private static List<String> messagesOf(final List<ILoggingEvent> warnings) {
		return warnings.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}

	private static String endedBy(final Admission answer) {
		return "The feed of a writer of this node ends, as its chain answered an entry with " + answer;
	}

	/* What the listener heard changes each time, beginning with a pause, since it starts out writable */
	private static void assertToldChangesOnly(final List<Boolean> told) {
		IntStream.range(0, told.size()).forEach(index -> assertThat(told.get(index),
			is(index == 0 ? Boolean.FALSE : !told.get(index - 1))));
	}

	private static boolean lastTold(final List<Boolean> told) {
		return told.isEmpty() || told.get(told.size() - 1);
	}

	@SneakyThrows
	private static void awaitIn(final CountDownLatch latch) {
		assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
	}

	private static ExecutorService daemonThreads(final int count) {
		return Executors.newFixedThreadPool(count, body -> {
			final Thread thread = new Thread(body, "outbox-test");

			thread.setDaemon(true);
			return thread;
		});
	}

	/* What the outbox warns about while the action runs, read as ChainFleet#warningsWhile reads the host's */
	private static List<ILoggingEvent> warningsWhile(final Runnable action) {
		ChainOutboxTest.class.getModule().addReads(ModuleLayer.boot().findModule("ch.qos.logback.core")
			.orElseThrow());

		final Logger logger = (Logger) LoggerFactory.getLogger(ChainOutbox.class);
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

	@Provide
	Arbitrary<Optional<Admission>> answers() {
		return Arbitraries.frequencyOf(Tuple.of(8, Arbitraries.just(Optional.of(Admission.ACCEPTED))),
			Tuple.of(8, Arbitraries.just(Optional.of(Admission.FULL))),
			Tuple.of(3, Arbitraries.of(TAKEN).map(Optional::of)),
			Tuple.of(1, Arbitraries.just(Optional.<Admission>empty())),
			Tuple.of(1, Arbitraries.of(ENDING).map(Optional::of)));
	}

	@Provide
	Arbitrary<Admission> taken() {
		return Arbitraries.of(TAKEN);
	}

	private List<Entry> mine(final int count) {
		return ChainFleet.entries(ChainFleet.key(WRITER), fleet.chain(), count, ChainFleet.NOW);
	}

	/* The chain of gn-1 holds as many entries of another writer as it has room for */
	private void fillTheChain() {
		final List<Entry> crowd = ChainFleet.entries(ChainFleet.key(2), fleet.chain(), 5, ChainFleet.NOW - EARLIER);

		host.receive(fleet.client("gn-1", 2), ChainEntry.builder().entries(crowd).build());
	}

	private void makeRoom() {
		fleet.clock.addAndGet(UNTIL_THE_CROWD_EXPIRES);
		host.maintain();
	}

	/* The entries of the writer that gn-1 flooded to the other members */
	private List<Entry> floodedByGn1() {
		return fleet.deliveries.stream().filter(delivery -> delivery.from().equals("gn-1"))
			.map(ChainFleet.Delivery::packet).filter(ChainEntry.class::isInstance)
			.flatMap(packet -> ((ChainEntry) packet).getEntries().stream())
			.filter(entry -> entry.header().writer().equals(ChainFleet.writer(WRITER))).toList();
	}

	@Example
	public void postsThroughTheHostWhileTheChainHasRoom() {
		final List<Entry> entries = mine(3);

		entries.forEach(outbox::accept);
		outbox.tick();

		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
		assertThat(outbox.isWritable(), is(true));
		assertThat(announced, is(empty()));
	}

	@Example
	public void pausesTheWriterWhenItsOwnChainHasNoRoom() {
		fillTheChain();
		mine(1).forEach(outbox::accept);

		assertThat(outbox.isWritable(), is(false));
		assertThat(host.pauses().refusedFrom(fleet.chain(), ChainFleet.writer(WRITER)).getAsLong(), is(0L));
	}

	@Example
	public void placesWhatWaitedForRoomOnceItIsBackAndTellsOnlyThen() {
		final List<Entry> entries = mine(3);

		fillTheChain();
		entries.forEach(outbox::accept);
		outbox.tick();
		assertThat(announced, contains(false));

		makeRoom();
		outbox.tick();

		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), equalTo(entries));
		assertThat(outbox.isWritable(), is(true));
		assertThat(announced, contains(false, true));
	}

	@Example
	public void announcesNothingWhenTheWaitIsOverWithinOneTick() {
		fillTheChain();
		mine(1).forEach(outbox::accept);
		makeRoom();
		outbox.tick();

		assertThat(outbox.isWritable(), is(true));
		assertThat(announced, is(empty()));
	}

	@Example
	public void offersEntriesAMemberRefusedAgain() {
		final Entry entry = mine(1).get(0);

		outbox.accept(entry);
		fleet.deliver();
		host.refused(fleet.stream("gn-1", "gn-0"), EntryRefused.of(entry, Admission.FULL));
		assertThat(outbox.isWritable(), is(false));

		fleet.deliveries.clear();
		outbox.tick();
		fleet.deliver();

		assertThat(outbox.isWritable(), is(true));
		assertThat(fleet.deliveries.stream().filter(delivery -> delivery.from().equals("gn-1")
			&& delivery.packet() instanceof ChainEntry).count(), is(3L));
	}

	/* A member's refusal pauses the writer but leaves nothing waiting, while an entry the chain has no room for waits
	   until it is placed */
	@Example
	public void isDrainedWhileNothingWaitsWhateverPausesTheWriter() {
		final Entry entry = mine(1).get(0);

		outbox.accept(entry);
		fleet.deliver();
		host.refused(fleet.stream("gn-1", "gn-0"), EntryRefused.of(entry, Admission.FULL));
		assertThat(outbox.isWritable(), is(false));
		assertThat(outbox.isDrained(), is(true));

		fillTheChain();
		mine(2).subList(1, 2).forEach(outbox::accept);
		assertThat(outbox.isDrained(), is(false));

		makeRoom();
		outbox.tick();
		assertThat(outbox.isDrained(), is(true));
	}

	@Example
	public void offersWhatAMemberRefusedWhileEntriesWaitedAgainAndWhatWaitedOnce() {
		final List<Entry> entries = mine(2);

		outbox.accept(entries.get(0));
		fleet.deliver();
		host.refused(fleet.stream("gn-1", "gn-0"), EntryRefused.of(entries.get(0), Admission.FULL));
		fillTheChain();
		outbox.accept(entries.get(1));
		assertThat(host.pauses().refusedFrom(fleet.chain(), ChainFleet.writer(WRITER)), is(OptionalLong.of(0)));

		makeRoom();
		fleet.deliver();
		fleet.deliveries.clear();
		outbox.tick();
		fleet.deliver();

		assertThat(floodedByGn1(), containsInAnyOrder(entries.get(0), entries.get(0), entries.get(0), entries.get(1),
			entries.get(1), entries.get(1)));
		assertThat(outbox.isWritable(), is(true));
		assertThat(announced, is(empty()));
	}

	@Example
	public void takesNothingMoreOnceTheHostLetsGoOfItsChain() {
		final List<Entry> entries = mine(3);

		outbox.accept(entries.get(0));
		fleet.settings.set(Optional.empty());
		host.refresh();
		outbox.accept(entries.get(1));
		outbox.tick();

		assertThat(outbox.isWritable(), is(false));
		assertThat(announced, contains(false));

		fleet.settings.set(Optional.of(ChainFleet.tinyChains()));
		host.refresh();
		outbox.accept(entries.get(2));
		outbox.tick();

		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), is(empty()));
		assertThat(host.chain(fleet.chain()).orElseThrow().heldBytes(), is(0L));
		assertThat(outbox.isWritable(), is(false));
		assertThat(announced, contains(false));
	}

	@Example
	public void queuesEveryEntryAfterOneWithoutRoomAndPostsThemInOrderOnTheNextTick() {
		final Scripted scripted = Scripted.answering(Admission.FULL);

		scripted.accept(0, 3);

		assertThat(scripted.host().posted, contains(FEED.get(0)));
		assertThat(scripted.host().pauses().refusedFrom(CHAIN, ChainFleet.writer(WRITER)), is(OptionalLong.of(0)));
		assertThat(scripted.outbox().isWritable(), is(false));
		assertThat(scripted.told(), is(empty()));

		scripted.outbox().tick();

		assertThat(scripted.host().posted, contains(FEED.get(0), FEED.get(0), FEED.get(1), FEED.get(2)));
		assertThat(scripted.outbox().isWritable(), is(true));
		assertThat(scripted.told(), is(empty()));

		scripted.accept(3, 4);
		assertThat(scripted.postedSeqs(), contains(0L, 0L, 1L, 2L, 3L));
	}

	@Example
	public void offersTheEntryWithoutRoomAgainButNotTheOneBeforeIt() {
		final Scripted scripted = Scripted.answering(Admission.ACCEPTED, Admission.FULL);

		scripted.accept(0, 3);

		assertThat(scripted.postedSeqs(), contains(0L, 1L));
		assertThat(scripted.host().pauses().refusedFrom(CHAIN, ChainFleet.writer(WRITER)), is(OptionalLong.of(1)));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 1L, 1L, 2L));
		assertThat(scripted.host().placed, equalTo(FEED.subList(0, 3)));
	}

	@Example
	public void keepsTheEntryInFrontAndTellsOfThePauseOnceWhileTheReplicaHasNoRoom() {
		final Scripted scripted = Scripted.answering(Admission.FULL, Admission.FULL, Admission.FULL);

		scripted.accept(0, 3);
		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L));
		assertThat(scripted.host().pauses().refusedFrom(CHAIN, ChainFleet.writer(WRITER)), is(OptionalLong.of(0)));
		assertThat(scripted.told(), contains(false));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 0L));
		assertThat(scripted.told(), contains(false));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 0L, 0L, 1L, 2L));
		assertThat(scripted.outbox().isWritable(), is(true));
		assertThat(scripted.told(), contains(false, true));
	}

	@Example
	public void tellsNothingWhileTheAnswerStaysTheSame() {
		final Scripted scripted = Scripted.answering();

		scripted.accept(0, 2);
		scripted.outbox().tick();
		scripted.outbox().tick();

		assertThat(scripted.told(), is(empty()));
	}

	@Example
	public void leavesWhatIsWrittenDuringATickForTheNextOne() {
		final Scripted scripted = Scripted.answering(Admission.FULL);

		scripted.accept(0, 1);
		scripted.host().whilePosting.set(() -> scripted.accept(1, 2));
		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L));
		assertThat(scripted.outbox().isWritable(), is(false));
		assertThat(scripted.told(), contains(false));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 1L));
		assertThat(scripted.told(), contains(false, true));
	}

	@Example
	public void putsAnEntryWithoutRoomInFrontOfThoseWrittenWhileItWasPosted() {
		final Scripted scripted = Scripted.answering(Admission.FULL);

		scripted.host().whilePosting.set(() -> scripted.accept(1, 3));
		scripted.accept(0, 1);

		assertThat(scripted.postedSeqs(), contains(0L));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 1L, 2L));
	}

	@Example
	public void placesNothingWhileAnEntryOfAWriterIsOnItsWay() {
		final Scripted scripted = Scripted.answering();

		scripted.host().whilePosting.set(() -> {
			scripted.outbox().tick();
			scripted.accept(1, 2);
			scripted.outbox().tick();
		});
		scripted.accept(0, 1);

		assertThat(scripted.postedSeqs(), contains(0L));
		assertThat(scripted.outbox().isWritable(), is(false));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 1L));
		assertThat(scripted.outbox().isWritable(), is(true));
	}

	@Example
	public void staysWritableWhileItPostsAnEntryOfAWriterThatNothingHoldsBack() {
		final Scripted scripted = Scripted.answering();
		final List<Boolean> meanwhile = new ArrayList<>();

		scripted.host().whilePosting.set(() -> meanwhile.add(scripted.outbox().isWritable()));
		scripted.accept(0, 1);

		assertThat(meanwhile, contains(true));
	}

	@Example
	public void staysUnwritableWhileItPlacesWhatWaited() {
		final Scripted scripted = Scripted.answering(Admission.FULL);
		final List<Boolean> meanwhile = new ArrayList<>();

		scripted.accept(0, 1);
		scripted.host().whilePosting.set(() -> meanwhile.add(scripted.outbox().isWritable()));
		scripted.outbox().tick();

		assertThat(meanwhile, contains(false));
		assertThat(scripted.outbox().isWritable(), is(true));
	}

	@Example
	public void isNotWritableWhileAnEntryWaitsThoughNothingPausesTheWriter() {
		final Scripted scripted = Scripted.answering(Admission.FULL);

		scripted.accept(0, 1);
		scripted.host().pauses().resume(CHAIN, ChainFleet.writer(WRITER));

		assertThat(scripted.outbox().isWritable(), is(false));
	}

	@Example
	public void isNotWritableWhileAMemberPausesTheWriterThoughNothingWaits() {
		final Scripted scripted = Scripted.answering();

		scripted.accept(0, 1);
		scripted.host().pauses().pause(EntryRefused.of(FEED.get(0), Admission.FULL));

		assertThat(scripted.outbox().isWritable(), is(false));

		scripted.outbox().tick();

		assertThat(scripted.outbox().isWritable(), is(true));
		assertThat(scripted.host().posted, contains(FEED.get(0)));
	}

	@Property(generation = GenerationMode.EXHAUSTIVE)
	public void letsGoOfAnEntryTheChainHoldsOrIsPast(@ForAll("taken") Admission answer) {
		final Scripted direct = Scripted.answering(answer);
		final Scripted waited = Scripted.answering(Admission.FULL, answer);

		direct.accept(0, 2);
		direct.outbox().tick();
		waited.accept(0, 2);
		waited.outbox().tick();

		assertThat(direct.postedSeqs(), contains(0L, 1L));
		assertThat(waited.postedSeqs(), contains(0L, 0L, 1L));

		for (final Scripted scripted : List.of(direct, waited)) {
			assertThat(scripted.outbox().isWritable(), is(true));
			assertThat(scripted.told(), is(empty()));
		}
	}

	/* An entry the chain will never take would leave a gap that every later entry of the feed waits behind, so it
	   ends the outbox as a chain that is gone does, and the log hears of it once */
	@Example
	public void takesNothingMoreOnceItsChainIsGoneOrWillNeverTakeAnEntry() {
		for (final Admission answer : ENDING) {
			final Scripted scripted = Scripted.answering(answer);
			final List<ILoggingEvent> warnings = warningsWhile(() -> {
				scripted.accept(0, 2);
				scripted.host().pauses().pause(EntryRefused.of(FEED.get(0), Admission.FULL));

				assertThat(answer.name(), scripted.host().posted, contains(FEED.get(0)));
				assertThat(answer.name(), scripted.outbox().isWritable(), is(false));

				scripted.outbox().tick();
				scripted.accept(2, 3);
				scripted.outbox().tick();
			});

			assertThat(answer.name(), scripted.host().posted, contains(FEED.get(0)));
			assertThat(answer.name(), scripted.host().isPaused(), is(false));
			assertThat(answer.name(), scripted.outbox().isWritable(), is(false));
			assertThat(answer.name(), scripted.outbox().hasEnded(), is(true));
			assertThat(answer.name(), scripted.told(), contains(false));
			assertThat(answer.name(), messagesOf(warnings), contains(endedBy(answer)));
		}
	}

	@Example
	public void dropsWhatWaitedOnceItsChainIsGoneOrWillNeverTakeTheFirst() {
		for (final Admission answer : ENDING) {
			final Scripted scripted = Scripted.answering(Admission.FULL, answer);
			final List<ILoggingEvent> warnings = warningsWhile(() -> {
				scripted.accept(0, 3);
				scripted.outbox().tick();
				scripted.accept(3, 5);
				scripted.outbox().tick();
			});

			assertThat(answer.name(), scripted.postedSeqs(), contains(0L, 0L));
			assertThat(answer.name(), scripted.outbox().isWritable(), is(false));
			assertThat(answer.name(), scripted.outbox().hasEnded(), is(true));
			assertThat(answer.name(), scripted.told(), contains(false));
			assertThat(answer.name(), messagesOf(warnings), contains(endedBy(answer)));
		}
	}

	/* An entry is stamped as it is written, so one that waited for room longer than the chain keeps entries is too
	   old for it, and every later entry of the writer would wait behind the gap it left until that expired */
	@Example
	public void endsTheFeedOfAnEntryThatWaitedForRoomLongerThanTheChainKeepsEntries() {
		final FeedWriter feed = new FeedWriter(ChainFleet.key(WRITER), fleet.chain());

		fillTheChain();
		ChainFleet.write(feed, 2, ChainFleet.NOW).forEach(outbox::accept);
		outbox.tick();
		assertThat(announced, contains(false));

		fleet.clock.addAndGet(RETENTION_MILLIS + 1);
		host.maintain();
		outbox.tick();
		ChainFleet.write(feed, 1, fleet.clock.get()).forEach(outbox::accept);
		outbox.tick();

		final Chain chain = host.chain(fleet.chain()).orElseThrow();

		assertThat(chain.heads().containsKey(ChainFleet.writer(WRITER)), is(false));
		assertThat(chain.heldBytes(), is(0L));
		assertThat(outbox.isWritable(), is(false));
		assertThat(outbox.hasEnded(), is(true));
		assertThat(announced, contains(false));
	}

	/* The writer holds the latest time it stamped, so once the clock stepped back further than the chain keeps
	   entries, every entry it writes is ahead of the chain's clock until the clock is back */
	@Example
	public void endsTheFeedWhenTheClockStepsBackFurtherThanTheChainKeepsEntries() {
		final FeedWriter feed = new FeedWriter(ChainFleet.key(WRITER), fleet.chain());
		final List<Entry> before = ChainFleet.write(feed, 1, fleet.clock.get());

		before.forEach(outbox::accept);
		fleet.clock.addAndGet(-RETENTION_MILLIS - 1);
		ChainFleet.write(feed, 2, fleet.clock.get()).forEach(outbox::accept);
		outbox.tick();

		assertThat(outbox.isWritable(), is(false));
		assertThat(outbox.hasEnded(), is(true));
		assertThat(announced, contains(false));

		fleet.clock.addAndGet(RETENTION_MILLIS + 1);
		ChainFleet.write(feed, 1, fleet.clock.get()).forEach(outbox::accept);
		outbox.tick();

		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), equalTo(before));
		assertThat(host.chain(fleet.chain()).orElseThrow().heldBytes(), is(0L));
		assertThat(announced, contains(false));
	}

	@Example
	public void waitsWithAnEntryTheHostFailedToTakeAndSaysOnlyWhatFailed() {
		final Scripted scripted = Scripted.answering(throwingFirst());
		final List<ILoggingEvent> warnings = warningsWhile(() -> scripted.accept(0, 2));

		assertThat(warnings.stream().map(ILoggingEvent::getFormattedMessage).toList(),
			contains("Posting an entry of this node failed with java.lang.IllegalStateException"));
		assertThat(scripted.host().pauses().refusedFrom(CHAIN, ChainFleet.writer(WRITER)), is(OptionalLong.of(0)));
		assertThat(scripted.outbox().isWritable(), is(false));

		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 1L));
		assertThat(scripted.outbox().isWritable(), is(true));
		assertThat(scripted.told(), is(empty()));
	}

	@Example
	public void ignoresAMissingEntry() {
		final Scripted scripted = Scripted.answering();

		scripted.outbox().accept(null);
		scripted.outbox().tick();

		assertThat(scripted.host().posted, is(empty()));
		assertThat(scripted.outbox().isWritable(), is(true));
	}

	@Property(tries = 300)
	public void postsEveryEntryInTheOrderOfTheFeedUntilTheChainTakesItOrNeverWill(
		@ForAll @Size(max = 40) List<Boolean> writes,
		@ForAll @Size(max = 60) List<@From("answers") Optional<Admission>> answers) {

		final Scripted scripted = Scripted.answering(call -> call < answers.size()
			? answers.get(call).orElseThrow(() -> new IllegalStateException(DETAIL)) : Admission.ACCEPTED);
		int written = 0;

		for (final boolean write : writes) {
			final int told = scripted.told().size();

			if (write) {
				scripted.accept(written, written + 1);
				written++;
				assertThat(scripted.told().size(), is(told));
			} else {
				scripted.outbox().tick();
				assertThat(lastTold(scripted.told()), is(scripted.outbox().isWritable()));
			}

			final boolean ended = hasEnded(scripted.host());

			assertThat(scripted.outbox().isWritable(), is(!ended && scripted.host().placed.size() == written));
			assertThat(scripted.outbox().hasEnded(), is(ended));
		}

		/* A tick posts once at least while anything waits, so the answers run out before the ticks do */
		IntStream.rangeClosed(0, answers.size()).forEach(tick -> scripted.outbox().tick());
		assertPostedInFeedOrder(scripted.host());
		assertToldChangesOnly(scripted.told());

		if (hasEnded(scripted.host())) {
			assertThat(scripted.outbox().isWritable(), is(false));
			assertThat(lastTold(scripted.told()), is(false));
		} else {
			assertThat(scripted.host().placed, equalTo(FEED.subList(0, written)));
			assertThat(scripted.outbox().isWritable(), is(true));
			assertThat(lastTold(scripted.told()), is(true));
		}
	}

	@Example
	public void placesEveryEntryOnceAndInOrderWhileATickRunsAlongsideTheWriter() throws Exception {
		final ExecutorService threads = daemonThreads(2);

		try {
			for (int round = 0; round < ROUNDS; round++) {
				final Scripted scripted = Scripted.answering(call -> call % 3 == 1 ? Admission.FULL
					: Admission.ACCEPTED);
				final AtomicReference<Thread> ticker = new AtomicReference<>();
				final CountDownLatch go = new CountDownLatch(1);
				final Future<?> writing = threads.submit(() -> {
					awaitIn(go);
					scripted.accept(0, FEED.size());
				});
				final Future<?> ticking = threads.submit(() -> {
					final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

					ticker.set(Thread.currentThread());
					awaitIn(go);

					while (scripted.host().placed.size() < FEED.size() && System.nanoTime() < deadline) {
						scripted.outbox().tick();
					}

					scripted.outbox().tick();
				});

				go.countDown();
				writing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				ticking.get(2 * TIMEOUT_SECONDS, TimeUnit.SECONDS);

				assertThat(scripted.host().placed, equalTo(FEED));
				assertPostedInFeedOrder(scripted.host());
				assertToldChangesOnly(scripted.told());
				assertThat(scripted.tellers(), everyItem(sameInstance(ticker.get())));
				assertThat(scripted.outbox().isWritable(), is(true));
				assertThat(lastTold(scripted.told()), is(true));
				assertThat(scripted.host().isPaused(), is(false));
			}
		} finally {
			threads.shutdownNow();
		}
	}

	@Example
	public void keepsPostingAfterAnErrorLeftTheHostWhileAnEntryWasPosted() {
		final Scripted scripted = Scripted.answering(call -> {
			if (call == 0) {
				throw new StackOverflowError();
			}

			return Admission.ACCEPTED;
		});

		Expect.assertThrows(StackOverflowError.class, () -> scripted.accept(0, 1));
		assertThat(scripted.outbox().isWritable(), is(false));
		scripted.outbox().tick();
		scripted.accept(1, 2);

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 1L));
		assertThat(scripted.outbox().isWritable(), is(true));
	}

	@Example
	public void keepsPlacingAfterAnErrorLeftTheHostWhileATickPlacedAnEntry() {
		final Scripted scripted = Scripted.answering(call -> {
			if (call == 1) {
				throw new StackOverflowError();
			}

			return call == 0 ? Admission.FULL : Admission.ACCEPTED;
		});

		scripted.accept(0, 2);
		Expect.assertThrows(StackOverflowError.class, scripted.outbox()::tick);
		scripted.outbox().tick();

		assertThat(scripted.postedSeqs(), contains(0L, 0L, 0L, 1L));
		assertThat(scripted.outbox().isWritable(), is(true));
	}

	@Example
	public void tellsAgainOnTheNextTickWhenTheListenerFailed() {
		final AtomicInteger calls = new AtomicInteger();
		final List<Boolean> heard = new ArrayList<>();
		final ChainOutbox flaky = new ChainOutbox(host, fleet.chain(), ChainFleet.writer(WRITER), writable -> {
			if (calls.getAndIncrement() == 0) {
				throw new IllegalStateException(DETAIL);
			}

			heard.add(writable);
		});

		fillTheChain();
		mine(1).forEach(flaky::accept);

		final List<ILoggingEvent> warnings = warningsWhile(flaky::tick);

		assertThat(warnings.stream().map(ILoggingEvent::getFormattedMessage).toList(), contains(
			"Telling the owner of a writer whether it may write failed with java.lang.IllegalStateException"));
		assertThat(heard, is(empty()));
		flaky.tick();
		assertThat(heard, contains(false));
		flaky.tick();
		assertThat(heard, contains(false));
	}

	/* The outbox of an exit's session, which stems what it is handed and passes over the stream to gn-0 */
	private ChainOutbox stemming() {
		return new ChainOutbox(host, fleet.chain(), ChainFleet.writer(WRITER), announced::add, ChainOutbox.Mode.STEM,
			Set.of(fleet.stream("gn-1", "gn-0")));
	}

	/* What the host wrote to each other member so far, taken off the streams */
	private Map<String, List<Object>> written() {
		final Map<String, List<Object>> written = new LinkedHashMap<>();

		for (final String peer : List.of("gn-0", "gn-2", "gn-3")) {
			final List<Object> packets = new ArrayList<>();
			final EmbeddedChannel stream = fleet.stream("gn-1", peer);

			for (Object packet = stream.readOutbound(); packet != null; packet = stream.readOutbound()) {
				packets.add(packet);
			}

			written.put(peer, packets);
		}

		return written;
	}

	private static String onlyOneOf(final Map<String, List<Object>> written) {
		final List<String> to = written.entrySet().stream().filter(sent -> !sent.getValue().isEmpty())
			.map(Map.Entry::getKey).toList();

		assertThat(to.size(), is(1));
		return to.get(0);
	}

	@Example
	public void anExitsOutboxStemsWhatItIsHandedInsteadOfPostingIt() {
		final ChainOutbox stems = stemming();
		final List<Entry> entries = mine(3);

		entries.forEach(stems::accept);

		final Map<String, List<Object>> written = written();
		final String successor = onlyOneOf(written);

		assertThat(successor, is(not("gn-0")));
		assertThat(written.get(successor), equalTo(entries.stream().map(entry -> (Object) new ChainStem(List.of(entry)))
			.toList()));
		assertThat(host.chain(fleet.chain()).orElseThrow().entries(), is(empty()));
		assertThat(stems.isWritable(), is(true));
	}

	/* The chain takes five entries, its reserve included, and those five wait in the stem, so the sixth waits in the
	   outbox and the writer is paused */
	@Example
	public void waitsWhileTheReplicaWouldHaveNoRoomCountingTheStem() {
		final ChainOutbox stems = stemming();

		mine(6).forEach(stems::accept);

		assertThat(written().values().stream().mapToLong(List::size).sum(), is(5L));
		assertThat(stems.isWritable(), is(false));
		assertThat(host.pauses().refusedFrom(fleet.chain(), ChainFleet.writer(WRITER)), is(OptionalLong.of(5)));
	}

	@Example
	public void stemsAgainWhatTheSuccessorRefused() {
		final ChainOutbox stems = stemming();

		final List<Entry> entries = mine(2);

		entries.forEach(stems::accept);

		final String successor = onlyOneOf(written());

		host.refused(fleet.stream("gn-1", successor), EntryRefused.of(entries.get(0), Admission.FULL));
		assertThat(stems.isWritable(), is(false));
		stems.tick();

		assertThat(stems.isWritable(), is(true));
		assertThat(written().get(successor), equalTo(List.of(new ChainStem(entries))));
	}

	/* What an exit stems is placed once it came back by flood, so its outbox has not drained while the stem still
	   holds any of it, though nothing waits in the outbox itself */
	@Example
	public void anExitsOutboxHasNotDrainedWhileTheStemHoldsItsEntries() {
		final ChainOutbox stems = stemming();
		final List<Entry> entries = mine(2);

		entries.forEach(stems::accept);

		final EmbeddedChannel successor = fleet.stream("gn-1", onlyOneOf(written()));

		assertThat(stems.isDrained(), is(false));
		host.receive(successor, ChainEntry.builder().entries(entries.subList(0, 1)).build());
		assertThat(stems.isDrained(), is(false));
		host.receive(successor, ChainEntry.builder().entries(entries.subList(1, 2)).build());
		assertThat(stems.isDrained(), is(true));
	}
}
