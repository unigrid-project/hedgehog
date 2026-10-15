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

package org.unigrid.hedgehog.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.ledger.Action.Broadcast;
import org.unigrid.hedgehog.ledger.Action.Decide;
import org.unigrid.hedgehog.ledger.Action.Schedule;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* The node is the glue between the ledger, the engine and the sign state, so these tests drive it the way a driver
   does and look at what reaches the store, the ledger and the actions that come back */
public class LedgerNodeTest {
	private static final List<byte[]> SOLO = seeds(1, 1);
	private static final List<byte[]> FOUR = seeds(1, 4);
	private static final int FOUR_VALIDATORS = 4;
	private static final long START = 10_000;
	private static final Mint DUE = mint(1, 700);

	@FunctionalInterface
	private interface Event {
		List<Action> run() throws IOException;
	}

	/* Keeps every state it is given, in memory, so that a test can see what was saved by the time a call returned.
	   It starts out holding `loaded`, if there is one, and refuses to save while `failing` is set. */
	private static final class RecordingStore implements SignStore {
		private final List<SignState> saved = new ArrayList<>();
		private final List<Integer> bounds = new ArrayList<>();
		private final SignState loaded;
		private boolean failing;
		private int loads;
		private int closes;

		RecordingStore() {
			this(null);
		}

		RecordingStore(SignState loaded) {
			this.loaded = loaded;
		}

		@Override
		public Optional<SignState> load() {
			loads++;
			return saved.isEmpty() ? Optional.ofNullable(loaded) : Optional.of(last());
		}

		@Override
		public void save(SignState state, int largestProof) throws IOException {
			if (failing) {
				throw new IOException("The disk is full");
			}

			saved.add(state);
			bounds.add(largestProof);
		}

		@Override
		public void close() {
			closes++;
		}

		SignState last() {
			return saved.get(saved.size() - 1);
		}
	}

	private static SignStore failingWith(RuntimeException refusal, IOException failure) {
		return new SignStore() {
			@Override
			public Optional<SignState> load() {
				return Optional.empty();
			}

			@Override
			public void save(SignState state, int largestProof) throws IOException {
				if (failure != null) {
					throw failure;
				}

				throw refusal;
			}
		};
	}

	private static final SignStore UNTOUCHED = new SignStore() {
		@Override
		public Optional<SignState> load() {
			throw new AssertionError("An observer reads no sign state");
		}

		@Override
		public void save(SignState state, int largestProof) {
			throw new AssertionError("An observer writes no sign state");
		}

		@Override
		public void close() {
			throw new AssertionError("An observer has no sign state to close");
		}
	};

	private static Path blocksOf(FileSystem fileSystem, String name) {
		return fileSystem.getPath("/" + name + "/blocks.log");
	}

	private static Path signsOf(FileSystem fileSystem, String name) {
		return fileSystem.getPath("/" + name + "/sign.state");
	}

	/* The sign state of the node of that name, with the rule of a real disk that a file in use is not opened by a
	   second node: a test that starts a node over the files of another closes the other first */
	private static SignStateFile signStateOf(FileSystem fileSystem, String name) {
		return StrictLocks.signStateOf(signsOf(fileSystem, name));
	}

	private static Ledger ledger(FileSystem fileSystem, String name, LedgerAuthority authority, List<byte[]> seeds,
		LongSupplier clock) throws IOException {
		return new Ledger(state(1000, authority, seeds), BlockLog.open(blocksOf(fileSystem, name)), clock);
	}

	/* A ledger of its own, of the four validators, that has a mint waiting: what a peer that made a block works from */
	private static Ledger withMint(FileSystem fileSystem, AtomicLong clock) throws IOException {
		final Ledger leader = ledger(fileSystem, "leader", ANY_MINT, FOUR, clock::get);

		leader.submit(mint(1, 500));
		return leader;
	}

	private static LedgerNode node(Ledger ledger, int validator, SignStore store, LongSupplier clock) {
		return new LedgerNode(ledger, seed(validator), Timeouts.DEFAULT, store, clock);
	}

	private static LedgerNode observer(Ledger ledger, SignStore store, LongSupplier clock) {
		return new LedgerNode(ledger, null, Timeouts.DEFAULT, store, clock);
	}

	private static <T extends Message> Stream<T> messages(List<Action> actions, Class<T> type) {
		return actions.stream().filter(Broadcast.class::isInstance).map(action -> ((Broadcast) action).message())
			.filter(type::isInstance).map(type::cast);
	}

	private static List<Proposal> proposalsIn(List<Action> actions) {
		return messages(actions, Proposal.class).toList();
	}

	private static List<Ballot> ballotsIn(List<Action> actions) {
		return messages(actions, Ballot.class).toList();
	}

	private static List<Block> decidedIn(List<Action> actions) {
		return actions.stream().filter(Decide.class::isInstance).map(action -> ((Decide) action).block()).toList();
	}

	private static Ballot vote(Ledger ledger, Ballot.Type type, int validator, long height, Block block) {
		return Ballot.signed(type, seed(validator), ledger.chainId(), height, 0, block.hash());
	}

	/* The state is saved at most once per call, before the call returns, and what the store holds then is everything
	   that the call signed and sends out for a height that is still open: a block that is decided and committed needs
	   no signatures kept. Whether a call saves at all depends on whether the state changed. */
	private static List<Action> saving(RecordingStore store, Ledger ledger, AccountKey self, Event event)
		throws IOException {
		final int before = store.saved.size();
		final List<Action> actions = event.run();

		assertThat(store.saved.size(), lessThanOrEqualTo(before + 1));
		store.bounds.forEach(bound -> assertThat(bound, equalTo(ledger.validators().size())));
		assertKept(store.last(), ledger, self, actions);
		return actions;
	}

	private static void assertRefusesEveryEvent(LedgerNode node) {
		final Ballot ballot = ConsensusFixtures.ballot(Ballot.Type.PREVOTE, 2, 0, null);

		assertThrows(IllegalStateException.class, node::start);
		assertThrows(IllegalStateException.class, node::tick);
		assertThrows(IllegalStateException.class, node::onWork);
		assertThrows(IllegalStateException.class, () -> node.onMessage(ballot));
		assertThrows(IllegalStateException.class, () -> node.onTimeout(new Timeout(Timeout.Kind.PROPOSE, 1, 0)));
	}

	private static void assertKept(SignState kept, Ledger ledger, AccountKey self, List<Action> actions) {
		final List<Proposal> proposals = proposalsIn(actions).stream()
			.filter(proposal -> proposal.sender().equals(self) && proposal.height() > ledger.height()).toList();
		final List<Ballot> ballots = ballotsIn(actions).stream()
			.filter(ballot -> ballot.validator().equals(self) && ballot.height() > ledger.height()).toList();

		proposals.forEach(proposal -> assertThat(kept.proposals(), hasItem(proposal)));
		ballots.forEach(ballot -> assertThat(kept.ballots(), hasItem(ballot)));
	}

	/* A state that is plausible for the height and says nothing is signed in it, of an engine in the middle of a view */
	private static SignState midView(long height) {
		return new SignState(height, true, 2, Consensus.Step.PREVOTE, 3, null, -1, null, -1, List.of(), List.of());
	}

	@Example
	public void shouldDecideAndCommitOnItsOwnWhenItIsTheOnlyValidator() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile store = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 1, store, clock::get);

			assertThat(node.start(), is(empty()));
			assertThat(node.submit(mint(1, 500)), equalTo(Optional.empty()));

			final List<Action> actions = node.onWork();

			assertThat(ledger.height(), equalTo(1L));
			assertThat(ledger.balanceOf(account(1)), equalTo(500L));
			assertThat(decidedIn(actions), hasSize(1));
			assertThat(decidedIn(actions).get(0).getHeight(), equalTo(1L));
			assertThat(messages(actions, Decision.class).toList(), hasSize(1));
			assertThat(actions.get(actions.size() - 1), instanceOf(Decide.class));
			assertThat(node.height(), equalTo(2L));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	@Example
	public void shouldDecideTheNextBlockWhenAnotherTransactionComesLater() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final LedgerNode node = node(ledger, 1, signStateOf(fileSystem, "a"), clock::get);

			node.start();
			node.submit(mint(1, 1000));
			node.onWork();

			clock.addAndGet(1000);
			assertThat(node.submit(paying(ledger, 100, 0, 0)), equalTo(Optional.empty()));

			final List<Action> actions = node.onWork();

			assertThat(ledger.height(), equalTo(2L));
			assertThat(ledger.balanceOf(account(2)), equalTo(100L));
			assertThat(decidedIn(actions).get(0).getHeight(), equalTo(2L));
			assertThat(node.height(), equalTo(3L));
		}
	}

	/* With a quorum of one the engine decides again as soon as it starts, so a call can commit more than one block */
	@Example
	public void shouldCommitEveryBlockThatTheOnlyValidatorDecidesInOneCall() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile store = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 1, store, clock::get);

			node.start();
			IntStream.rangeClosed(1, Block.MAX_TRANSACTIONS + 1).forEach(n -> node.submit(mint(n, 1)));

			final List<Action> actions = node.onWork();

			assertThat(ledger.height(), equalTo(2L));
			assertThat(decidedIn(actions), hasSize(2));
			assertThat(node.height(), equalTo(3L));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(3))));
		}
	}

	@Example
	public void shouldSaveBeforeEveryCallReturnsWhatItSigned() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final RecordingStore store = new RecordingStore();
			final LedgerNode node = node(ledger, 1, store, clock::get);
			final AccountKey self = key(1);

			assertThat(saving(store, ledger, self, node::start), is(empty()));
			node.submit(mint(1, 500));

			final List<Action> proposed = saving(store, ledger, self, node::onWork);
			final Proposal proposal = proposalsIn(proposed).get(0);
			final Block block = proposal.block();

			assertThat(ballotsIn(proposed), hasSize(1));
			assertThat(saving(store, ledger, self, node::tick), not(empty()));
			saving(store, ledger, self, () -> node.onTimeout(((Schedule) proposed.stream()
				.filter(Schedule.class::isInstance).findFirst().orElseThrow()).timeout()));
			saving(store, ledger, self, () -> node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 2, 1, block)));

			final List<Action> locked = saving(store, ledger, self,
				() -> node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 3, 1, block)));

			assertThat(ballotsIn(locked).get(0).type(), equalTo(Ballot.Type.PRECOMMIT));
			saving(store, ledger, self, () -> node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 2, 1, block)));

			final List<Action> decided = saving(store, ledger, self,
				() -> node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 3, 1, block)));

			assertThat(decidedIn(decided), hasSize(1));
			assertThat(ledger.height(), equalTo(1L));
			assertThat(store.last().height(), equalTo(2L));
			assertThat(store.saved.stream().map(SignState::height).toList(), equalTo(List.of(1L, 1L, 1L, 2L)));
		}
	}

	@Example
	public void shouldKeepWhatItProposedWhenItComesBackFromItsFiles() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode before = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				signStateOf(fileSystem, "a"), clock::get);

			before.start();
			before.submit(mint(1, 500));

			final Proposal proposal = proposalsIn(before.onWork()).get(0);
			final List<Message> said = before.outbox();

			before.close();

			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);

			reopened.submit(mint(2, 700));

			final LedgerNode after = node(reopened, 1, signStateOf(fileSystem, "a"), clock::get);
			final List<Action> resumed = after.start();

			assertThat(after.outbox(), equalTo(said));
			assertThat(proposalsIn(resumed), equalTo(List.of(proposal)));
			assertThat(proposalsIn(after.tick()), equalTo(List.of(proposal)));
		}
	}

	@Example
	public void shouldKeepWhatRestoringSignedBeforeItReturnsIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final SignStateFile file = signStateOf(fileSystem, "a");
			final Ledger first = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode before = node(first, 1, file, clock::get);

			before.start();
			before.submit(mint(1, 500));
			before.onWork();

			final SignState full = file.load().orElseThrow();

			assertThat(full.ballots(), hasSize(1));
			file.save(new SignState(full.height(), full.running(), full.view(), Consensus.Step.PROPOSE,
				full.nextView(), null, -1, null, -1, full.proposals(), List.of()), FOUR_VALIDATORS);

			before.close();

			final Ledger second = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final List<Action> resumed = node(second, 1, file, clock::get).start();

			assertThat(ballotsIn(resumed), hasSize(1));
			assertThat(file.load().orElseThrow().ballots(), equalTo(ballotsIn(resumed)));
		}
	}

	/* A decided engine is saved as one that runs, so a state of an engine that decided is kept apart from one that
	   the ledger has not yet taken in: the block is committed when the engine decides it again */
	@Example
	public void shouldCommitTheBlockARestoredEngineDecidesAgain() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final Consensus engine = new Consensus(ledger, ledger.chainId(), seed(1), Timeouts.DEFAULT);
			final SignStateFile file = signStateOf(fileSystem, "a");

			ledger.submit(mint(1, 500));
			assertThat(decidedIn(engine.start(1, ledger.validators(), START)), hasSize(1));
			file.save(engine.snapshot(), 1);

			final LedgerNode node = node(ledger, 1, file, clock::get);
			final List<Action> actions = node.start();

			assertThat(decidedIn(actions), hasSize(1));
			assertThat(ledger.height(), equalTo(1L));
			assertThat(file.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	@Example
	public void shouldIgnoreAStateOfAHeightThatIsAlreadyDecided() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");

			fundFirstAndThirdAccount(SOLO, START, ledger);
			file.save(midView(1), 1);

			final LedgerNode node = node(ledger, 1, file, clock::get);

			assertThat(node.start(), is(empty()));
			assertThat(node.height(), equalTo(2L));
			assertThat(file.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	@Example
	public void shouldIgnoreAStateOfAHeightBelowTheTip() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");

			fundFirstAndThirdAccount(SOLO, START, ledger);
			ledger.submit(mint(5, 1));
			ledger.commit(nextBlock(ledger, SOLO, START));
			file.save(midView(1), 1);

			final LedgerNode node = node(ledger, 1, file, clock::get);

			assertThat(ledger.height(), equalTo(2L));
			assertThat(node.start(), is(empty()));
			assertThat(node.height(), equalTo(3L));
			assertThat(file.load(), equalTo(Optional.of(SignState.empty(3))));
		}
	}

	/* The state is saved after the block of the height before it is committed, so a state above the next height can
	   only be there if the block log lost blocks, and starting afresh would sign again at heights signed at */
	@Example
	public void shouldRefuseToStartWhenTheBlockLogIsBehindTheSignState() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");

			fundFirstAndThirdAccount(SOLO, START, ledger);

			for (final long height : List.of(3L, 11L)) {
				file.save(midView(height), 1);

				final LedgerNode node = node(ledger, 1, file, clock::get);
				final BlockLogBehindException refused = assertThrows(BlockLogBehindException.class, node::start);

				assertThat(refused.getMessage(), containsString("block log is behind the sign state"));
				assertThat(refused.getMessage(), containsString("height 1"));
				assertThat(refused.getMessage(), containsString("height " + height));
				assertThat(node.height(), equalTo(0L));
				assertRefusesEveryEvent(node);
				assertThat(file.load(), equalTo(Optional.of(midView(height))));
			}
		}
	}

	@Example
	public void shouldTellWhatToDoWhenTheBlockLogIsBehindTheSignState() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final SignStateFile file = signStateOf(fileSystem, "a");

			file.save(midView(5), 1);

			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get), 1, file, clock::get);
			final BlockLogBehindException refused = assertThrows(BlockLogBehindException.class, node::start);

			assertThat(refused.getMessage(), containsString("Restore the tail of the block log from a peer"));
			assertThat(refused.getMessage(), containsString("never delete the sign state"));
		}
	}

	/* Two nodes over one sign state could each sign something different for a view: the second does not start */
	@Example
	public void shouldRefuseToStartOverTheFilesOfAnotherNodeUntilItIsClosed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode first = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				signStateOf(fileSystem, "a"), clock::get);
			final LedgerNode second = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				signStateOf(fileSystem, "a"), clock::get);

			first.start();

			final FileLockedException refused = assertThrows(FileLockedException.class, second::start);

			assertThat(refused.getMessage(), containsString("another process or node holds the data directory"));
			assertRefusesEveryEvent(second);
			assertThat(first.tick(), is(empty()));
			first.close();

			final LedgerNode third = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				signStateOf(fileSystem, "a"), clock::get);

			assertThat(third.start(), is(empty()));
			third.close();
		}
	}

	@Example
	public void shouldLetGoOfTheStoreAndTakeNoFurtherEventWhenClosed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore store = new RecordingStore();
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 1, store, clock::get);

			node.start();
			node.close();
			assertThat(store.closes, equalTo(1));
			assertRefusesEveryEvent(node);
			assertThrows(IllegalStateException.class, node::outbox);
			assertThat(node.submit(mint(1, 500)), equalTo(Optional.empty()));
			assertThat(node.height(), equalTo(1L));
		}
	}

	@Example
	public void shouldNotTouchAStoreWhenAnObserverIsClosed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode node = observer(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), UNTOUCHED,
				clock::get);

			node.start();
			node.close();
			assertThrows(IllegalStateException.class, node::tick);
		}
	}

	@Example
	public void shouldRefuseAValidatorWithoutASignStore() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);

			assertThrows(NullPointerException.class, () -> node(ledger, 1, null, clock::get));
		}
	}

	@Example
	public void shouldTakeAnObserverWithoutASignStore() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode node = observer(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), null, clock::get);

			assertThat(node.start(), is(empty()));
			assertThat(node.tick(), is(empty()));
		}
	}

	@Example
	public void shouldStartAnObserverWhoseBlockLogIsBehindTheSignState() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");

			file.save(midView(5), 1);

			assertThat(observer(ledger, file, clock::get).start(), is(empty()));
			assertThat(file.load(), equalTo(Optional.of(midView(5))));
		}
	}

	@Example
	public void shouldStopWhenTheStateFileIsDamaged() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Path path = signsOf(fileSystem, "a");

			Files.createDirectories(path.getParent());
			Files.write(path, "not a sign state".getBytes(StandardCharsets.US_ASCII));

			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				StrictLocks.signStateOf(path), clock::get);

			assertThrows(CorruptSignStateException.class, node::start);
			assertThat(node.height(), equalTo(0L));
			assertRefusesEveryEvent(node);
		}
	}

	@Example
	public void shouldStopWhenTheStateIsOneThatThisNodeCannotHaveWritten() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final SignStateFile file = signStateOf(fileSystem, "a");
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final Block block = proposed(withMint(fileSystem, clock), START);
			final Ballot foreign = vote(ledger, Ballot.Type.PREVOTE, 2, 1, block);

			file.save(new SignState(1, true, 0, Consensus.Step.PREVOTE, 1, null, -1, null, -1, List.of(),
				List.of(foreign)), FOUR_VALIDATORS);

			final LedgerNode node = node(ledger, 1, file, clock::get);

			assertThrows(IllegalArgumentException.class, node::start);
			assertRefusesEveryEvent(node);
			assertThat(file.load().orElseThrow().ballots(), equalTo(List.of(foreign)));
		}
	}

	@Example
	public void shouldStartAnObserverWithoutTouchingADamagedFile() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Path path = signsOf(fileSystem, "a");

			Files.createDirectories(path.getParent());
			Files.write(path, "not a sign state".getBytes(StandardCharsets.US_ASCII));

			final byte[] before = Files.readAllBytes(path);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = observer(ledger, StrictLocks.signStateOf(path), clock::get);

			assertThat(node.start(), is(empty()));
			assertThat(node.onMessage(new Decision(nextBlock(withMint(fileSystem, clock), FOUR, START))),
				not(empty()));
			assertThat(Files.readAllBytes(path), equalTo(before));
		}
	}

	@Example
	public void shouldDecideAsAnObserverAndWriteNothing() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = observer(ledger, UNTOUCHED, clock::get);
			final Block block = nextBlock(withMint(fileSystem, clock), FOUR, START);

			assertThat(node.start(), is(empty()));

			final List<Action> actions = node.onMessage(new Decision(block));

			assertThat(decidedIn(actions), equalTo(List.of(block)));
			assertThat(ledger.height(), equalTo(1L));
			assertThat(node.height(), equalTo(2L));
			assertThat(node.tick(), is(empty()));
			assertThat(node.onWork(), is(empty()));
		}
	}

	@Example
	public void shouldCatchUpOnADecisionForTheNextHeight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final SignStateFile store = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 2, store, clock::get);
			final Block block = nextBlock(withMint(fileSystem, clock), FOUR, START);

			node.start();

			final List<Action> actions = node.onMessage(new Decision(block));

			assertThat(decidedIn(actions), equalTo(List.of(block)));
			assertThat(messages(actions, Decision.class).toList(), equalTo(List.of(new Decision(block))));
			assertThat(ledger.height(), equalTo(1L));
			assertThat(ledger.balanceOf(account(1)), equalTo(500L));
			assertThat(node.height(), equalTo(2L));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	@Example
	public void shouldRefuseADecisionWhoseCertificateIsNotEnough() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 2, signStateOf(fileSystem, "a"), clock::get);
			final Block thin = nextBlock(withMint(fileSystem, clock), FOUR, 2, START);

			node.start();

			assertThat(node.onMessage(new Decision(thin)), is(empty()));
			assertThat(ledger.height(), equalTo(0L));
			assertThat(node.height(), equalTo(1L));
		}
	}

	@Example
	public void shouldChangeNothingOnADecisionForAHeightThatIsOver() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final SignStateFile store = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 2, store, clock::get);
			final Block block = nextBlock(withMint(fileSystem, clock), FOUR, START);

			node.start();
			node.onMessage(new Decision(block));

			final byte[] tip = ledger.tipHash();

			assertThat(node.onMessage(new Decision(block)), is(empty()));
			assertThat(ledger.height(), equalTo(1L));
			assertThat(ledger.tipHash(), equalTo(tip));
			assertThat(node.height(), equalTo(2L));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	@Example
	public void shouldReturnNothingFromATickOfAQuietNode() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				signStateOf(fileSystem, "a"), clock::get);

			node.start();

			for (int second = 0; second < 5; second++) {
				clock.addAndGet(1000);
				assertThat(node.tick(), is(empty()));
			}
		}
	}

	@Example
	public void shouldStartTheViewOnATickOnceAMintIsDue() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), FOUR, clock::get);
			final LedgerNode node = node(ledger, 1, signStateOf(fileSystem, "a"), clock::get);

			assertThat(node.start(), is(empty()));
			assertThat(node.tick(), is(empty()));

			clock.set(20_000);

			final List<Action> ticked = node.tick();

			assertThat(proposalsIn(ticked), hasSize(1));
			assertThat(proposalsIn(ticked).get(0).block().getTransactions(), equalTo(List.of(DUE)));
			assertThat(ballotsIn(ticked), hasSize(1));
			assertThat(schedulesIn(ticked).stream().map(Schedule::timeout).toList(),
				equalTo(List.of(new Timeout(Timeout.Kind.PROPOSE, 1, 0))));
		}
	}

	private static List<Schedule> schedulesIn(List<Action> actions) {
		return actions.stream().filter(Schedule.class::isInstance).map(Schedule.class::cast).toList();
	}

	/* Timers kept by the Timeout they are for, and replaced when the same one is armed again: what a driver is free
	   to do, and what starves a timer that is armed again every second */
	private static final class KeyedTimers {
		private final Map<Timeout, Long> due = new HashMap<>();
		private final List<Action> seen = new ArrayList<>();
		private final AtomicLong clock;
		private final LedgerNode node;

		KeyedTimers(AtomicLong clock, LedgerNode node) {
			this.clock = clock;
			this.node = node;
		}

		void take(List<Action> actions) {
			seen.addAll(actions);
			schedulesIn(actions).forEach(schedule -> due.put(schedule.timeout(), clock.get() + schedule.delayMillis()));
		}

		void passOneSecond() throws IOException {
			clock.addAndGet(1000);

			for (final Timeout timeout : due.entrySet().stream().filter(entry -> entry.getValue() <= clock.get())
				.map(Map.Entry::getKey).toList()) {
				due.remove(timeout);
				take(node.onTimeout(timeout));
			}

			take(node.tick());
		}
	}

	@Example
	public void shouldLetATimerFireThatTheDriverIsTickedPastEverySecond() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 2, signStateOf(fileSystem, "a"), clock::get);
			final KeyedTimers driver = new KeyedTimers(clock, node);

			ledger.submit(mint(1, 500));
			driver.take(node.start());
			assertThat(schedulesIn(driver.seen).stream().map(Schedule::timeout).toList(),
				equalTo(List.of(new Timeout(Timeout.Kind.PROPOSE, 1, 0))));
			driver.passOneSecond();
			driver.passOneSecond();
			assertThat(ballotsIn(driver.seen), is(empty()));
			assertThat(schedulesIn(node.tick()), is(empty()));

			driver.passOneSecond();
			driver.passOneSecond();

			assertThat(ballotsIn(driver.seen).stream().filter(Ballot::isNil).distinct().map(Ballot::type).toList(),
				equalTo(List.of(Ballot.Type.PREVOTE)));
		}
	}

	@Example
	public void shouldSayAgainOnATickWhatItSigned() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				signStateOf(fileSystem, "a"), clock::get);

			node.start();
			node.submit(mint(1, 500));

			final List<Action> proposed = node.onWork();
			final List<Action> ticked = node.tick();

			assertThat(proposalsIn(ticked), equalTo(proposalsIn(proposed)));
			assertThat(ballotsIn(ticked), equalTo(ballotsIn(proposed)));
		}
	}

	@Example
	public void shouldNotSaveAgainOnATickOfAQuietNode() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore store = new RecordingStore();
			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1, store, clock::get);

			node.start();
			assertThat(store.saved, hasSize(1));

			for (int second = 0; second < 5; second++) {
				clock.addAndGet(1000);
				assertThat(node.tick(), is(empty()));
			}

			assertThat(store.saved, hasSize(1));
		}
	}

	@Example
	public void shouldSaveOnATickThatChangesTheStateAndOnlyThen() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", dueAt(Map.of(DUE, 20_000L)), FOUR, clock::get);
			final RecordingStore store = new RecordingStore();
			final LedgerNode node = node(ledger, 1, store, clock::get);

			node.start();
			node.tick();
			assertThat(store.saved, hasSize(1));

			clock.set(20_000);
			assertThat(proposalsIn(node.tick()), hasSize(1));
			assertThat(store.saved, hasSize(2));
			assertThat(store.last().proposals(), hasSize(1));

			clock.addAndGet(1000);
			assertThat(proposalsIn(node.tick()), hasSize(1));
			assertThat(store.saved, hasSize(2));
		}
	}

	@Example
	public void shouldNotSaveAfterLoadingAStateThatTheEngineLeavesAsItIs() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore first = new RecordingStore();
			final LedgerNode before = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1, first, clock::get);

			before.start();
			before.submit(mint(1, 500));
			before.onWork();

			final RecordingStore second = new RecordingStore(first.last());
			final LedgerNode after = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1, second, clock::get);

			assertThat(proposalsIn(after.start()), hasSize(1));
			assertThat(proposalsIn(after.tick()), hasSize(1));
			assertThat(second.saved, is(empty()));
		}
	}

	@Example
	public void shouldHandBackTheValidatorsTheEngineHeardSayTwoThingsInOneView() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 1, signStateOf(fileSystem, "a"), clock::get);
			final Block block = proposedBy(node);
			final Ballot first = vote(ledger, Ballot.Type.PREVOTE, 2, 1, block);
			final Ballot second = Ballot.signed(Ballot.Type.PREVOTE, seed(2), ledger.chainId(), 1, 0, null);

			node.onMessage(first);
			assertThat(node.evidence(), is(empty()));

			node.onMessage(second);
			assertThat(node.evidence(), equalTo(List.of(new BallotBox.Equivocation(first, second))));
		}
	}

	/* The engine has moved on from what the store holds, so a node that carried on could save a state in which that
	   is not to be seen: it refuses everything that runs the engine, and the store is not asked again */
	@Example
	public void shouldRefuseEveryEventAfterASaveFailed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore store = new RecordingStore();
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 1, store, clock::get);

			store.failing = true;
			assertThrows(IOException.class, node::start);

			store.failing = false;
			assertRefusesEveryEvent(node);
			assertThat(store.saved, is(empty()));
			assertThat(store.loads, equalTo(1));
		}
	}

	@Example
	public void shouldKeepTheQuestionsOfAFailedNodeWorking() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore store = new RecordingStore();
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 1, store, clock::get);

			store.failing = true;
			assertThrows(IOException.class, node::start);

			assertThat(node.height(), equalTo(1L));
			assertThat(node.ledger(), equalTo(ledger));
			assertThat(node.submit(mint(1, 500)), equalTo(Optional.empty()));
			assertThat(ledger.pending(), equalTo(1));
		}
	}

	@Example
	public void shouldTakeAHeartbeatIntoTheLedgerWithoutRunningTheEngine() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore store = new RecordingStore();
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 1, store, clock::get);

			store.failing = true;
			assertThrows(IOException.class, node::start);

			for (int validator = 2; validator <= FOUR_VALIDATORS; validator++) {
				final Heartbeat beat = Heartbeat.signed(seed(validator), ledger.chainId(), ledger.height(),
					ledger.tipHash(), START);

				assertThat(node.submit(beat), equalTo(Optional.empty()));
				assertThat(node.submit(beat).isPresent(), is(true));
			}

			assertThat(ledger.isProgressing(START, 1000), is(true));
		}
	}

	/* The engine of a node that failed holds what the failed event signed and did not save, and a driver that polls
	   the outbox to say it again would send exactly that */
	@Example
	public void shouldNotHandOutWhatAFailedEventSigned() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore first = new RecordingStore();
			final LedgerNode before = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1, first, clock::get);
			final Block block = proposedBy(before);
			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final RecordingStore second = new RecordingStore(first.last());
			final LedgerNode node = node(reopened, 1, second, clock::get);

			node.start();
			assertThat(precommitsOf(node.outbox()), is(empty()));
			node.onMessage(vote(reopened, Ballot.Type.PREVOTE, 2, 1, block));
			second.failing = true;
			assertThrows(IOException.class, () -> node.onMessage(vote(reopened, Ballot.Type.PREVOTE, 3, 1, block)));
			assertThrows(IllegalStateException.class, node::outbox);
			assertThat(second.saved, is(empty()));
		}
	}

	@Example
	public void shouldReadTheClockOnceForAWholeCall() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final List<Long> reads = new ArrayList<>();
			final AtomicLong time = new AtomicLong(START);
			final LongSupplier counting = () -> {
				reads.add(time.get());
				return time.getAndAdd(1);
			};
			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, SOLO, time::get), 1,
				signStateOf(fileSystem, "a"), counting);

			node.start();
			assertThat(reads, hasSize(1));
			node.submit(mint(1, 500));
			assertThat(decidedIn(node.onWork()), hasSize(1));
			assertThat(reads, hasSize(2));
			node.tick();
			assertThat(reads, hasSize(3));
			node.onTimeout(new Timeout(Timeout.Kind.PROPOSE, 2, 0));
			assertThat(reads, hasSize(4));
		}
	}

	@Example
	public void shouldLeaveTheStoredStateAloneUntilItHasStarted() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Path path = signsOf(fileSystem, "a");
			final LedgerNode before = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				StrictLocks.signStateOf(path), clock::get);

			before.start();
			before.submit(mint(1, 500));
			before.onWork();

			final byte[] kept = Files.readAllBytes(path);

			before.close();

			final LedgerNode after = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				StrictLocks.signStateOf(path), clock::get);

			assertThat(after.tick(), is(empty()));
			assertThat(after.onWork(), is(empty()));
			assertThat(Files.readAllBytes(path), equalTo(kept));
		}
	}

	@Example
	public void shouldNotHandBackActionsWhenTheStateCannotBeSaved() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);

			assertThrows(IOException.class, () -> node(ledger, 1,
				failingWith(null, new IOException("The disk is full")), clock::get).start());
			assertThrows(IllegalArgumentException.class, () -> node(ledger, 1,
				failingWith(new IllegalArgumentException("Too large"), null), clock::get).start());
		}
	}

	/* The engine has decided, but the ledger could not take the block in: nothing was saved or let out, and the node
	   refuses everything after it, as a save of the decided engine would hide the block the ledger lacks. A start from
	   the files finds the state that was last saved. */
	@Example
	public void shouldLetACommitThatFailsReachTheCallerAndKeepTheLastSavedState() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final BlockLog log = BlockLog.open(blocksOf(fileSystem, "a"));
			final Ledger ledger = new Ledger(state(1000, ANY_MINT, SOLO), log, clock::get);
			final SignStateFile store = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 1, store, clock::get);

			node.start();
			node.submit(mint(1, 500));
			log.close();

			assertThrows(IOException.class, node::onWork);
			assertThat(ledger.height(), equalTo(0L));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(1))));
			assertRefusesEveryEvent(node);
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(1))));
			node.close();

			final LedgerNode restarted = node(ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get), 1, store,
				clock::get);

			assertThat(restarted.start(), is(empty()));
			assertThat(restarted.height(), equalTo(1L));
		}
	}

	private static final long WAIT_SECONDS = 5;

	/* A store on a real file that stops working when told to, as the disk does when the node dies in an event */
	private static final class CrashingStore implements SignStore {
		private final SignStore real;
		private boolean crashing;

		CrashingStore(SignStore real) {
			this.real = real;
		}

		@Override
		public Optional<SignState> load() throws IOException {
			return real.load();
		}

		@Override
		public void save(SignState state, int largestProof) throws IOException {
			if (crashing) {
				throw new IOException("The node died before the state was saved");
			}

			real.save(state, largestProof);
		}

		@Override
		public void close() throws IOException {
			real.close();
		}
	}

	/* A save that does not return until the test lets it, and gives up after a while so that nothing hangs */
	private static final class BlockingStore implements SignStore {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		@Override
		public Optional<SignState> load() {
			return Optional.empty();
		}

		@Override
		public void save(SignState state, int largestProof) throws IOException {
			entered.countDown();

			try {
				if (!release.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new IOException("The save was never let go");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("The save was interrupted", e);
			}
		}
	}

	/* The node proposes and prevotes at height 1 on real files and is left waiting for the votes of the others */
	private static Block proposedBy(LedgerNode node) throws IOException {
		node.start();
		node.submit(mint(1, 500));
		return proposalsIn(node.onWork()).get(0).block();
	}

	private static List<Ballot> precommitsOf(Collection<? extends Message> messages) {
		return messages.stream().filter(Ballot.class::isInstance).map(Ballot.class::cast)
			.filter(ballot -> ballot.type() == Ballot.Type.PRECOMMIT).toList();
	}

	/* The block is committed and the node dies on the save that follows: nothing of that event left it, and the
	   files, read by a new node, say that the height is decided */
	@Example
	public void shouldComeBackAtTheNextHeightWhenTheNodeDiesOnTheSaveAfterADecision() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");
			final CrashingStore store = new CrashingStore(file);
			final LedgerNode node = node(ledger, 1, store, clock::get);
			final Block block = proposedBy(node);

			node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 2, 1, block));
			node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 3, 1, block));
			node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 2, 1, block));

			final SignState beforeDeciding = file.load().orElseThrow();

			store.crashing = true;
			assertThrows(IOException.class, () -> node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 3, 1, block)));
			assertThat(ledger.height(), equalTo(1L));
			assertThat(file.load(), equalTo(Optional.of(beforeDeciding)));
			assertThat(beforeDeciding.height(), equalTo(1L));
			assertRefusesEveryEvent(node);
			node.close();

			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode back = node(reopened, 1, file, clock::get);
			final List<Action> actions = back.start();

			assertThat(reopened.height(), equalTo(1L));
			assertThat(back.height(), equalTo(2L));
			assertThat(actions, is(empty()));
			assertThat(file.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	/* The node signs its precommit in the very event that decides, and dies on the commit before it saved or sent
	   it: the files hold only the prevote. A node that comes back from them signs that precommit again, the same
	   one, as it is a signature over the same vote, and decides the same block. */
	@Example
	public void shouldSignTheSamePrecommitAgainWhenTheNodeDiesOnTheCommitOfWhatItDecided() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final BlockLog log = BlockLog.open(blocksOf(fileSystem, "a"));
			final Ledger ledger = new Ledger(state(1000, ANY_MINT, FOUR), log, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 1, file, clock::get);
			final Block block = proposedBy(node);

			node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 2, 1, block));
			node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 3, 1, block));
			node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 2, 1, block));

			final SignState beforeDeciding = file.load().orElseThrow();

			assertThat(beforeDeciding.lockedBlock(), is(nullValue()));
			assertThat(beforeDeciding.ballots(), hasSize(1));
			log.close();
			assertThrows(IOException.class, () -> node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 3, 1, block)));
			assertThat(ledger.height(), equalTo(0L));
			assertThat(file.load(), equalTo(Optional.of(beforeDeciding)));

			final List<Ballot> unsaved = List.of(vote(ledger, Ballot.Type.PRECOMMIT, 1, 1, block));

			node.close();

			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode back = node(reopened, 1, file, clock::get);
			final List<Action> signed = new ArrayList<>(back.start());

			signed.addAll(back.onMessage(vote(reopened, Ballot.Type.PRECOMMIT, 2, 1, block)));
			signed.addAll(back.onMessage(vote(reopened, Ballot.Type.PRECOMMIT, 3, 1, block)));
			signed.addAll(back.onMessage(vote(reopened, Ballot.Type.PREVOTE, 2, 1, block)));

			final List<Action> deciding = back.onMessage(vote(reopened, Ballot.Type.PREVOTE, 3, 1, block));

			signed.addAll(deciding);
			assertThat(precommitsOf(ballotsIn(signed)), equalTo(unsaved));
			assertThat(decidedIn(deciding).get(0).hash(), equalTo(block.hash()));
			assertThat(reopened.height(), equalTo(1L));
		}
	}

	/* Here the lock and the precommit were saved by an earlier event: the node comes back locked, says them again
	   at once, and needs the precommits of the others only */
	@Example
	public void shouldDecideTheSameBlockAgainWhenTheNodeDiesOnTheCommitAfterItLocked() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final BlockLog log = BlockLog.open(blocksOf(fileSystem, "a"));
			final Ledger ledger = new Ledger(state(1000, ANY_MINT, FOUR), log, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 1, file, clock::get);
			final Block block = proposedBy(node);

			node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 2, 1, block));
			node.onMessage(vote(ledger, Ballot.Type.PREVOTE, 3, 1, block));
			node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 2, 1, block));

			final SignState beforeDeciding = file.load().orElseThrow();

			assertThat(beforeDeciding.lockedBlock(), equalTo(block));
			log.close();
			assertThrows(IOException.class, () -> node.onMessage(vote(ledger, Ballot.Type.PRECOMMIT, 3, 1, block)));
			assertThat(ledger.height(), equalTo(0L));
			assertThat(file.load(), equalTo(Optional.of(beforeDeciding)));
			node.close();

			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode back = node(reopened, 1, file, clock::get);
			final List<Action> started = back.start();

			assertThat(precommitsOf(ballotsIn(started)), hasSize(1));
			assertThat(precommitsOf(ballotsIn(started)), equalTo(precommitsOf(beforeDeciding.ballots())));
			back.onMessage(vote(reopened, Ballot.Type.PRECOMMIT, 2, 1, block));

			final List<Action> deciding = back.onMessage(vote(reopened, Ballot.Type.PRECOMMIT, 3, 1, block));

			assertThat(ballotsIn(deciding), is(empty()));
			assertThat(decidedIn(deciding).get(0).hash(), equalTo(block.hash()));
			assertThat(reopened.height(), equalTo(1L));
		}
	}

	@Example
	public void shouldRefuseToStartAfterARealRunWhenTheTailOfTheBlockLogWasLost() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final BlockLog log = BlockLog.open(blocksOf(fileSystem, "a"));
			final Ledger ledger = new Ledger(state(1000, ANY_MINT, SOLO), log, clock::get);
			final SignStateFile file = signStateOf(fileSystem, "a");
			final LedgerNode node = node(ledger, 1, file, clock::get);

			node.start();
			node.submit(mint(1, 500));
			node.onWork();
			assertThat(ledger.height(), equalTo(1L));
			assertThat(file.load(), equalTo(Optional.of(SignState.empty(2))));

			node.close();
			log.close();
			Files.write(blocksOf(fileSystem, "a"), new byte[0]);

			final Ledger shortened = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final LedgerNode back = node(shortened, 1, file, clock::get);
			final BlockLogBehindException refused = assertThrows(BlockLogBehindException.class, back::start);

			assertThat(shortened.height(), equalTo(0L));
			assertThat(refused.getMessage(), containsString("height 0"));
			assertThat(refused.getMessage(), containsString("height 2"));
			assertThat(file.load(), equalTo(Optional.of(SignState.empty(2))));
		}
	}

	@FunctionalInterface
	private interface Turn {
		List<Action> on(LedgerNode node) throws IOException;
	}

	private static void assertWaitsForItsTurn(Turn second) throws Exception {
		assertWaitsForItsTurn(second, 2);
	}

	/* A save that does not return until it is let go, on a thread of its own, and then a second event on another
	   thread: it has to wait for its turn, as two events at once would corrupt the engine. A thread that waits for the
	   monitor of the node is blocked, which is what the second thread is looked for in; every wait has a limit, so a
	   node that lets both through fails the test and does not hang it. Each event reads the clock once, a question
	   not at all. */
	private static void assertWaitsForItsTurn(Turn second, int clockReads) throws Exception {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicInteger reads = new AtomicInteger();
			final BlockingStore store = new BlockingStore();
			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, FOUR, () -> START), 1, store,
				() -> {
					reads.incrementAndGet();
					return START;
				});
			final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
			final Thread holder = thread(node::start, failures);
			final Thread waiter = thread(() -> second.on(node), failures);

			try {
				holder.start();
				assertThat(store.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
				waiter.start();
				assertThat(isBlocked(waiter), is(true));
				assertThat(reads.get(), equalTo(1));
			} finally {
				store.release.countDown();
			}

			holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
			waiter.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
			assertThat(holder.isAlive() || waiter.isAlive(), is(false));
			assertThat(failures, is(empty()));
			assertThat(reads.get(), equalTo(clockReads));
		}
	}

	private static Thread thread(Event event, List<Throwable> failures) {
		final Thread thread = new Thread(() -> {
			try {
				event.run();
			} catch (IOException | RuntimeException e) {
				failures.add(e);
			}
		});

		thread.setDaemon(true);
		return thread;
	}

	private static boolean isBlocked(Thread thread) throws InterruptedException {
		final long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);

		while (System.nanoTime() < giveUp && isStillGoing(thread)) {
			Thread.sleep(1);
		}

		return thread.getState() == Thread.State.BLOCKED;
	}

	private static boolean isStillGoing(Thread thread) {
		return thread.getState() == Thread.State.NEW || thread.getState() == Thread.State.RUNNABLE;
	}

	@Example
	public void shouldLetATickWaitForTheEventThatIsBeingSaved() throws Exception {
		assertWaitsForItsTurn(LedgerNode::tick);
	}

	@Example
	public void shouldLetAWorkEventWaitForTheEventThatIsBeingSaved() throws Exception {
		assertWaitsForItsTurn(LedgerNode::onWork);
	}

	@Example
	public void shouldLetAMessageWaitForTheEventThatIsBeingSaved() throws Exception {
		assertWaitsForItsTurn(node -> node.onMessage(ConsensusFixtures.ballot(Ballot.Type.PREVOTE, 2, 0, null)));
	}

	@Example
	public void shouldLetATimeoutWaitForTheEventThatIsBeingSaved() throws Exception {
		assertWaitsForItsTurn(node -> node.onTimeout(new Timeout(Timeout.Kind.PROPOSE, 1, 0)));
	}

	@Example
	public void shouldLetAQuestionAboutWhatItHoldsWaitForTheEventThatIsBeingSaved() throws Exception {
		assertWaitsForItsTurn(node -> {
			node.holds(ConsensusFixtures.ballot(Ballot.Type.PREVOTE, 2, 0, null));
			return List.of();
		}, 1);
	}

	@Example
	public void shouldSayWhatItHoldsOfTheOpenHeight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 2, signStateOf(fileSystem, "a"), clock::get);
			final Block block = nextBlock(withMint(fileSystem, clock), FOUR, START);
			final Ballot prevote = vote(ledger, Ballot.Type.PREVOTE, 1, 1, block);

			assertThat(node.holds(prevote), is(false));

			node.start();

			assertThat(node.holds(prevote), is(false));

			node.onMessage(prevote);

			assertThat(node.holds(prevote), is(true));
			assertThat(node.holds(vote(ledger, Ballot.Type.PRECOMMIT, 1, 1, block)), is(false));
			assertThat(node.holds(vote(ledger, Ballot.Type.PREVOTE, 3, 1, block)), is(false));
		}
	}

	/* Asking sends nothing, so unlike the outbox it keeps answering after the node failed */
	@Example
	public void shouldKeepSayingWhatItHoldsAfterTheNodeFailed() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final RecordingStore first = new RecordingStore();
			final LedgerNode before = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1, first, clock::get);
			final Block block = proposedBy(before);
			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final RecordingStore second = new RecordingStore(first.last());
			final LedgerNode node = node(reopened, 1, second, clock::get);
			final Ballot held = vote(reopened, Ballot.Type.PREVOTE, 2, 1, block);

			node.start();
			node.onMessage(held);
			second.failing = true;
			assertThrows(IOException.class, () -> node.onMessage(vote(reopened, Ballot.Type.PREVOTE, 3, 1, block)));
			assertThrows(IllegalStateException.class, node::outbox);

			assertThat(node.holds(held), is(true));
			assertThat(node.holds(vote(reopened, Ballot.Type.PRECOMMIT, 2, 1, block)), is(false));
		}
	}

	/* The commit and the start of the next height belong to the event that decides, so the engine of a node that
	   took the decision holds nothing of the height any more: the ledger is what tells it from then on */
	@Example
	public void shouldNotHoldADecisionOnceTheNodeHasMovedOn() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode node = node(ledger, 2, signStateOf(fileSystem, "a"), clock::get);
			final Decision decision = new Decision(nextBlock(withMint(fileSystem, clock), FOUR, START));

			node.start();

			assertThat(node.holds(decision), is(false));

			node.onMessage(decision);

			assertThat(node.holds(decision), is(false));
			assertThat(ledger.hashAt(1).orElseThrow(), equalTo(decision.block().hash()));
		}
	}

	@Example
	public void shouldSayWhetherItSigns() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, () -> START);

			assertThat(node(ledger, 1, new RecordingStore(), () -> START).signs(), is(true));
			assertThat(observer(ledger, UNTOUCHED, () -> START).signs(), is(false));
		}
	}
}
