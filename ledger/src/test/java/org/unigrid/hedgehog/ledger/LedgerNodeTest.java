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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.hamcrest.Matchers.not;
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

	/* Keeps every state it is given, in memory, so that a test can see what was saved by the time a call returned */
	private static final class RecordingStore implements SignStore {
		private final List<SignState> saved = new ArrayList<>();
		private final List<Integer> bounds = new ArrayList<>();

		@Override
		public Optional<SignState> load() {
			return saved.isEmpty() ? Optional.empty() : Optional.of(last());
		}

		@Override
		public void save(SignState state, int largestProof) {
			saved.add(state);
			bounds.add(largestProof);
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
	};

	private static FileSystem newFileSystem() {
		return Jimfs.newFileSystem(Configuration.unix());
	}

	private static Path blocksOf(FileSystem fileSystem, String name) {
		return fileSystem.getPath("/" + name + "/blocks.log");
	}

	private static Path signsOf(FileSystem fileSystem, String name) {
		return fileSystem.getPath("/" + name + "/sign.state");
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

	/* The state is saved once per call, before the call returns, and holds everything that the call signed and
	   sends out for a height that is still open: a block that is decided and committed needs no signatures kept */
	private static List<Action> saving(RecordingStore store, Ledger ledger, AccountKey self, Event event)
		throws IOException {
		final int before = store.saved.size();
		final List<Action> actions = event.run();

		assertThat(store.saved.size(), equalTo(before + 1));
		assertThat(store.bounds.get(before), equalTo(ledger.validators().size()));
		assertKept(store.last(), ledger, self, actions);
		return actions;
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
			final SignStateFile store = new SignStateFile(signsOf(fileSystem, "a"));
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
			final LedgerNode node = node(ledger, 1, new SignStateFile(signsOf(fileSystem, "a")), clock::get);

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
			final SignStateFile store = new SignStateFile(signsOf(fileSystem, "a"));
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
			assertThat(store.saved, hasSize(8));
		}
	}

	@Example
	public void shouldKeepWhatItProposedWhenItComesBackFromItsFiles() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode before = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				new SignStateFile(signsOf(fileSystem, "a")), clock::get);

			before.start();
			before.submit(mint(1, 500));

			final Proposal proposal = proposalsIn(before.onWork()).get(0);
			final Ledger reopened = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);

			reopened.submit(mint(2, 700));

			final LedgerNode after = node(reopened, 1, new SignStateFile(signsOf(fileSystem, "a")), clock::get);
			final List<Action> resumed = after.start();

			assertThat(after.outbox(), equalTo(before.outbox()));
			assertThat(proposalsIn(resumed), equalTo(List.of(proposal)));
			assertThat(proposalsIn(after.tick()), equalTo(List.of(proposal)));
		}
	}

	@Example
	public void shouldKeepWhatRestoringSignedBeforeItReturnsIt() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));
			final Ledger first = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final LedgerNode before = node(first, 1, file, clock::get);

			before.start();
			before.submit(mint(1, 500));
			before.onWork();

			final SignState full = file.load().orElseThrow();

			assertThat(full.ballots(), hasSize(1));
			file.save(new SignState(full.height(), full.running(), full.view(), Consensus.Step.PROPOSE,
				full.nextView(), null, -1, null, -1, full.proposals(), List.of()), FOUR_VALIDATORS);

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
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));

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
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));

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
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));

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
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));

			fundFirstAndThirdAccount(SOLO, START, ledger);

			for (final long height : List.of(3L, 11L)) {
				file.save(midView(height), 1);

				final LedgerNode node = node(ledger, 1, file, clock::get);
				final BlockLogBehindException refused = assertThrows(BlockLogBehindException.class, node::start);

				assertThat(refused.getMessage(), containsString("block log is behind the sign state"));
				assertThat(refused.getMessage(), containsString("height 1"));
				assertThat(refused.getMessage(), containsString("height " + height));
				assertThat(node.height(), equalTo(0L));
				assertThat(file.load(), equalTo(Optional.of(midView(height))));
			}
		}
	}

	@Example
	public void shouldStartAnObserverWhoseBlockLogIsBehindTheSignState() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get);
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));

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
				new SignStateFile(path), clock::get);

			assertThrows(CorruptSignStateException.class, node::start);
			assertThat(node.height(), equalTo(0L));
		}
	}

	@Example
	public void shouldStopWhenTheStateIsOneThatThisNodeCannotHaveWritten() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final SignStateFile file = new SignStateFile(signsOf(fileSystem, "a"));
			final Ledger ledger = ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get);
			final Block block = proposed(withMint(fileSystem, clock), START);
			final Ballot foreign = vote(ledger, Ballot.Type.PREVOTE, 2, 1, block);

			file.save(new SignState(1, true, 0, Consensus.Step.PREVOTE, 1, null, -1, null, -1, List.of(),
				List.of(foreign)), FOUR_VALIDATORS);

			assertThrows(IllegalArgumentException.class, () -> node(ledger, 1, file, clock::get).start());
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
			final LedgerNode node = observer(ledger, new SignStateFile(path), clock::get);

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
			final SignStateFile store = new SignStateFile(signsOf(fileSystem, "a"));
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
			final LedgerNode node = node(ledger, 2, new SignStateFile(signsOf(fileSystem, "a")), clock::get);
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
			final SignStateFile store = new SignStateFile(signsOf(fileSystem, "a"));
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
				new SignStateFile(signsOf(fileSystem, "a")), clock::get);

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
			final LedgerNode node = node(ledger, 1, new SignStateFile(signsOf(fileSystem, "a")), clock::get);

			assertThat(node.start(), is(empty()));
			assertThat(node.tick(), is(empty()));

			clock.set(20_000);

			final List<Action> ticked = node.tick();

			assertThat(proposalsIn(ticked), hasSize(1));
			assertThat(proposalsIn(ticked).get(0).block().getTransactions(), equalTo(List.of(DUE)));
			assertThat(ballotsIn(ticked), hasSize(1));
		}
	}

	@Example
	public void shouldSayAgainOnATickWhatItSigned() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final LedgerNode node = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				new SignStateFile(signsOf(fileSystem, "a")), clock::get);

			node.start();
			node.submit(mint(1, 500));

			final List<Action> proposed = node.onWork();
			final List<Action> ticked = node.tick();

			assertThat(proposalsIn(ticked), equalTo(proposalsIn(proposed)));
			assertThat(ballotsIn(ticked), equalTo(ballotsIn(proposed)));
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
				new SignStateFile(signsOf(fileSystem, "a")), counting);

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
				new SignStateFile(path), clock::get);

			before.start();
			before.submit(mint(1, 500));
			before.onWork();

			final byte[] kept = Files.readAllBytes(path);
			final LedgerNode after = node(ledger(fileSystem, "a", ANY_MINT, FOUR, clock::get), 1,
				new SignStateFile(path), clock::get);

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

	/* The engine has decided, but the ledger could not take the block in: nothing was saved or let out, so the node
	   is behind its engine, and a start from the files finds the state that was last saved */
	@Example
	public void shouldLetACommitThatFailsReachTheCallerAndKeepTheLastSavedState() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicLong clock = new AtomicLong(START);
			final BlockLog log = BlockLog.open(blocksOf(fileSystem, "a"));
			final Ledger ledger = new Ledger(state(1000, ANY_MINT, SOLO), log, clock::get);
			final SignStateFile store = new SignStateFile(signsOf(fileSystem, "a"));
			final LedgerNode node = node(ledger, 1, store, clock::get);

			node.start();
			node.submit(mint(1, 500));
			log.close();

			assertThrows(IOException.class, node::onWork);
			assertThat(ledger.height(), equalTo(0L));
			assertThat(store.load(), equalTo(Optional.of(SignState.empty(1))));

			final LedgerNode restarted = node(ledger(fileSystem, "a", ANY_MINT, SOLO, clock::get), 1, store,
				clock::get);

			assertThat(restarted.start(), is(empty()));
			assertThat(restarted.height(), equalTo(1L));
		}
	}
}
