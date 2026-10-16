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
import java.nio.file.FileSystem;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.PeerLimiter.Rate;
import org.unigrid.hedgehog.ledger.RecordingNetwork.RecordingPeer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.newFileSystem;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.nextBlock;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seeds;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.state;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.ANY_MINT;

/* A node that is behind fetches the blocks of a peer and applies them as it would a decision, one by one and in
   order. The peer is a ledger of its own that answers from its log, so what it serves is a real chain, and a test
   makes it serve something else only where the test is about that. */
public class LedgerDriverSyncTest {
	private static final int BLOCKS = 40;
	private static final long TIP = BLOCKS;

	/* A request timeout that no other timer of the driver or the node has, so that the property can tell the timer of a
	   request from the rest */
	private static final long PROPERTY_TIMEOUT = 4_321;

	/* The two chains of one genesis that the property draws on, built once for all of its tries */
	private static final List<Chain> PROPERTY_CHAINS = new ArrayList<>();

	/* Every peer is connected */
	private static final Supplier<Set<String>> EVERYONE = () -> new AbstractSet<>() {
		@Override
		public boolean contains(Object id) {
			return true;
		}

		@Override
		public Iterator<String> iterator() {
			return Collections.emptyIterator();
		}

		@Override
		public int size() {
			return 0;
		}
	};

	/* A score of 1 is a penalty, so a peer that is charged anything at all shows it */
	private static final Policy TOUCHY = new Policy(Policy.DEFAULT.rates(), Policy.DEFAULT.global(), 1, 10, 60_000,
		100);

	/* Committed blocks from the genesis of the validators; the amount minted tells chains of one genesis apart */
	private static final class Chain implements AutoCloseable {
		private final FileSystem fileSystem = newFileSystem();
		private final BlockLog log;
		private final Ledger ledger;

		private Chain(int blocks, long amount) throws IOException {
			log = BlockLog.open(fileSystem.getPath("/chain/blocks.log"));
			ledger = new Ledger(state(1000, ANY_MINT, DriverRig.FOUR), log);

			for (int n = 1; n <= blocks; n++) {
				ledger.submit(mint(n, amount));
				ledger.commit(nextBlock(ledger, DriverRig.FOUR, DriverRig.START + n));
			}
		}

		private Block block(long height) {
			return ledger.blocksFrom(height, 1).get(0);
		}

		@Override
		public void close() throws IOException {
			log.close();
			fileSystem.close();
		}
	}

	/* What a test knows of the driver that a server answers */
	private interface Watch {
		boolean ready();

		long height();

		/* Which chain the ledger holds: 'A', 'F' or '-' when it holds nothing */
		char chain();

		/* A number for a request that is made, in the order they are made */
		long stamp();

		/* Whether the driver waits for the answer to the request with that number: the latest one, and no one if the
		   driver has given up on it or has ended its sync */
		boolean current(long stamp);
	}

	/* The other end of a sync, which answers from a chain unless it is told to serve something else, to hold its
	   answers until they are released, or to fail */
	private static final class Server implements LedgerPeer {
		private record Held(BlockRequest request, CompletableFuture<BlockBatch> reply, long stamp) {
		}

		private final String id;
		private final Chain chain;
		private final List<BlockRequest> requests = new ArrayList<>();
		private final Deque<Held> held = new ArrayDeque<>();
		private Function<BlockRequest, List<Block>> serving;
		private boolean holding;
		private boolean emptying;
		private boolean tampering;
		private boolean dialled;
		private long delivered;
		private long crossServed;
		private long badNewServed;
		private int failuresLeft;
		private char tag = 'A';
		private Watch watch;
		private Throwable failure;

		private Server(String id, Chain chain) {
			this.id = id;
			this.chain = chain;
			this.serving = request -> chain.ledger.blocksFrom(request.from(), request.max());
		}

		private Server serving(Function<BlockRequest, List<Block>> what) {
			serving = what;
			return this;
		}

		private Server holding() {
			holding = true;
			return this;
		}

		private Server dialledByUs() {
			dialled = true;
			return this;
		}

		@Override
		public boolean dialled() {
			return dialled;
		}

		private List<Block> answer(Held asked) {
			if (emptying) {
				return List.of();
			}

			final BlockRequest request = asked.request();
			final List<Block> blocks = serving.apply(request);

			delivered += blocks.size();
			note(asked, blocks);
			return tampering ? spoiling(blocks, request.from() + 1) : blocks;
		}

		/* What the driver must make of this answer, as far as the test can tell: a batch of another chain than the
		   ledger holds, given to a driver that can take it, halts it, and a block that is new and false, in a batch
		   that it takes, makes the peer doubted. An answer to a request that the driver has given up is taken by no
		   one. */
		private void note(Held asked, List<Block> blocks) {
			final BlockRequest request = asked.request();

			if (watch != null && watch.ready() && watch.current(asked.stamp()) && !blocks.isEmpty()) {
				if (watch.chain() != '-' && watch.chain() != tag) {
					crossServed++;
				}

				if (tampering && blocks.size() >= 2 && request.from() + 1 > watch.height()) {
					badNewServed++;
				}
			}
		}

		private Server of(char chainTag) {
			tag = chainTag;
			return this;
		}

		private Server watched(Watch what) {
			watch = what;
			return this;
		}

		private Server emptying() {
			emptying = true;
			return this;
		}

		private boolean hasHeld() {
			return !held.isEmpty();
		}

		/* One answer is given and the next request is held again, if the server was holding */
		private void releaseIfHeld() {
			if (hasHeld()) {
				final Held next = held.remove();

				next.reply().complete(new BlockBatch(answer(next)));
			}
		}

		private void failIfHeld() {
			if (hasHeld()) {
				failHeld(new IllegalStateException("The request timed out"));
			}
		}

		private Server failing(Throwable cause) {
			failure = cause;
			return this;
		}

		/* The next requests get no answer, and the ones after them are answered */
		private Server failingNext(int times) {
			failuresLeft = times;
			return this;
		}

		private Status status() {
			return chain.ledger.status();
		}

		private Status statusAt(long height) {
			return new Status(chain.ledger.chainId(), height, chain.ledger.hashAt(height).orElseThrow());
		}

		/* The oldest answer that is held is given, and the ones that follow are not held any more */
		private void release() {
			final Held next = held.remove();

			holding = false;
			next.reply().complete(new BlockBatch(answer(next)));
		}

		private void failHeld(Throwable cause) {
			held.remove().reply().completeExceptionally(cause);
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public void send(Object wire) {
			/* What the driver says to this peer is of no interest here */
		}

		@Override
		public CompletableFuture<BlockBatch> request(BlockRequest request) {
			final CompletableFuture<BlockBatch> reply = new CompletableFuture<>();
			final Held asked = new Held(request, reply, watch == null ? 0 : watch.stamp());

			requests.add(request);

			if (failure != null) {
				reply.completeExceptionally(failure);
			} else if (failuresLeft > 0) {
				failuresLeft--;
				reply.completeExceptionally(new IllegalStateException("The request timed out"));
			} else if (holding) {
				held.add(asked);
			} else {
				reply.complete(new BlockBatch(answer(asked)));
			}

			return reply;
		}
	}

	private static DriverRig fresh() {
		return fresh(DriverRig.STRICT);
	}

	private static DriverRig fresh(Policy policy) {
		return new DriverRig(DriverRig.FOUR, seed(2), policy, new DirectSerial()).started();
	}

	private static DriverRig freshBelow(Checkpoint trusted) {
		return new DriverRig(DriverRig.FOUR, seed(2), DriverRig.STRICT, new DirectSerial(), trusting(trusted))
			.started();
	}

	private static DriverOptions trusting(Checkpoint trusted) {
		return new DriverOptions(DriverRig.TICK, DriverRig.HEARTBEAT, DriverRig.STATUS, DriverRig.GRACE,
			DriverRig.RESTART_WINDOW, DriverRig.QUEUE, DriverRig.PER_PEER, Optional.ofNullable(trusted));
	}

	/* A driver that starts over a log that holds the first blocks of the chain already, as after a restart */
	private static DriverRig startedOver(Chain chain, long blocks, Checkpoint trusted) throws IOException {
		return startedOver(chain, blocks, trusted, DriverRig.STRICT);
	}

	private static DriverRig startedOver(Chain chain, long blocks, Checkpoint trusted, Policy policy)
		throws IOException {
		return withBlocks(new DriverRig(DriverRig.FOUR, seed(2), policy, new DirectSerial(), trusting(trusted)), chain,
			blocks).started();
	}

	/* The log of the rig holds the first blocks of the chain before the driver starts */
	private static DriverRig withBlocks(DriverRig rig, Chain chain, long blocks) throws IOException {
		final BlockLog log = BlockLog.open(rig.fileSystem.getPath("/node/blocks.log"));

		for (long height = 1; height <= blocks; height++) {
			log.append(chain.block(height));
		}

		log.close();
		return rig;
	}

	private static Policy burstOf(Kind kind, int capacity) {
		final Map<Kind, Rate> rates = new EnumMap<>(Policy.DEFAULT.rates());

		rates.put(kind, new Rate(capacity, 1));
		return new Policy(rates, Policy.DEFAULT.global(), 100, 10, 60_000, 100);
	}

	private static Policy scoringSixIsAPenalty() {
		return new Policy(Policy.DEFAULT.rates(), Policy.DEFAULT.global(), 6, 10, 60_000, 100);
	}

	/* The same block with one signature of its certificate spoiled, which the hash does not cover */
	private static Block spoiled(Block block) {
		final List<Endorsement> endorsements = new ArrayList<>(block.getEndorsements());
		final byte[] signature = endorsements.get(0).signature();

		signature[10] ^= 1;
		endorsements.set(0, new Endorsement(endorsements.get(0).signer(), signature));
		return block.toBuilder().certificate(new Certificate(block.getCertificate().view(), endorsements)).build();
	}

	private static Block certifiedBy(Block block, List<Endorsement> endorsements) {
		return block.toBuilder().certificate(new Certificate(block.getCertificate().view(), endorsements)).build();
	}

	private static List<Block> spoiling(List<Block> blocks, long height) {
		return blocks.stream().map(block -> block.getHeight() == height ? spoiled(block) : block).toList();
	}

	private static List<Ballot> votesOf(RecordingPeer peer, int validator) {
		return peer.sent(Ballot.class).stream()
			.filter(ballot -> ballot.validator().equals(LedgerFixtures.key(validator))).toList();
	}

	@Example
	public void shouldFetchBlocksInBatchesFromAPeerAheadAndSignOnlyOnceItHasCaughtUp() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();
			final RecordingPeer watcher = rig.network.connect("W");

			rig.hears(server, server.status());
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			server.release();

			assertThat(server.requests,
				contains(new BlockRequest(1, 16), new BlockRequest(17, 16), new BlockRequest(33, 16)));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.ledger().tipHash(), equalTo(chain.ledger.tipHash()));
			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(watcher.sent(Ballot.class), is(empty()));
			assertThat(watcher.sent(Proposal.class), is(empty()));
		}
	}

	@Example
	public void shouldTellItsPeersWhereItStandsOnceWhenASyncEndsAndNotForwardTheBlocks() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);
			final RecordingPeer watcher = rig.network.connect("W");

			rig.hears(server, server.status());

			assertThat(watcher.sent(Status.class), equalTo(List.of(rig.ledger().status())));
			assertThat(watcher.sent(Decision.class), is(empty()));
		}
	}

	@Example
	public void shouldNotVoteBeforeItHasCaughtUpAndVoteAfterwards() throws IOException {
		try (DriverRig rig = fresh()) {
			final RecordingPeer watcher = rig.network.connect("W");
			final RecordingPeer proposer = rig.network.connect("P");

			rig.driver.onMessage(proposer, rig.proposal());

			assertThat(votesOf(watcher, 2), is(empty()));

			rig.passGrace();
			rig.driver.onMessage(proposer, rig.proposal());

			assertThat(votesOf(watcher, 2), hasSize(1));
		}
	}

	@Example
	public void shouldStopAtABlockWhoseCertificateIsFalseAndFinishWithAnotherPeer() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server liar = new Server("L", chain).serving(
				request -> spoiling(chain.ledger.blocksFrom(request.from(), request.max()), 20));
			final Server honest = new Server("H", chain);

			rig.hears(liar, liar.status());

			assertThat(rig.ledger().height(), equalTo(19L));
			assertThat(rig.limiter.isPenalised("L"), is(true));
			assertThat(liar.requests, hasSize(2));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.hears(honest, honest.status());

			assertThat(honest.requests, contains(new BlockRequest(20, 16), new BlockRequest(36, 16)));
			assertThat(liar.requests, hasSize(2));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldRefuseABatchThatSkipsTheBlockItWasAskedFor() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server skipping = new Server("S", chain).serving(
				request -> chain.ledger.blocksFrom(request.from() + 1, request.max()));

			rig.hears(skipping, skipping.status());

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(rig.limiter.isPenalised("S"), is(true));
			assertThat(skipping.requests, hasSize(1));
		}
	}

	@Example
	public void shouldRefuseABatchThatGivesBackBlocksItAlreadyHas() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server repeating = new Server("R", chain).serving(request -> request.from() == 1
				? chain.ledger.blocksFrom(1, 16) : chain.ledger.blocksFrom(1, request.max()));

			rig.hears(repeating, repeating.status());

			assertThat(rig.ledger().height(), equalTo(16L));
			assertThat(rig.limiter.isPenalised("R"), is(true));
			assertThat(repeating.requests, hasSize(2));
		}
	}

	@Example
	public void shouldSkipTheBlocksTheLedgerTookInWhileTheRequestWasOut() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain).holding();
			final RecordingPeer live = rig.network.connect("L");

			rig.hears(server, server.status());
			rig.driver.onMessage(live, new Decision(chain.block(1)));

			assertThat(rig.ledger().height(), equalTo(1L));

			server.release();

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.limiter.isPenalised("S"), is(false));
			assertThat(rig.built, equalTo(List.of(false)));
		}
	}

	@Example
	public void shouldNotSyncFromTwoPeersAtOnceAndTurnToTheOtherWhenTheFirstFails() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server first = new Server("A", chain).holding();
			final Server second = new Server("B", chain);

			rig.hears(first, first.status());
			rig.hears(second, second.status());

			assertThat(first.requests, hasSize(1));
			assertThat(second.requests, is(empty()));

			first.failHeld(new IllegalStateException("The request timed out"));
			first.failHeld(new IllegalStateException("The request timed out"));

			assertThat(first.requests, hasSize(2));
			assertThat(second.requests, hasSize(3));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.limiter.isPenalised("A"), is(false));
		}
	}

	@Example
	public void shouldEndASyncWhoseRequestFailsWithoutChargingThePeerAndClearItsClaim() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server failing = new Server("A", chain).failing(new IllegalStateException("The request timed out"));
			final Server other = new Server("B", chain);

			rig.hears(failing, failing.status());

			assertThat(failing.requests, hasSize(2));
			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));

			rig.hears(other, other.status());

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(failing.requests, hasSize(2));
		}
	}

	@Example
	public void shouldEndASyncWhoseRequestCannotEvenBeSent() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final LedgerPeer broken = new LedgerPeer() {
				@Override
				public String id() {
					return "A";
				}

				@Override
				public void send(Object wire) {
					throw new IllegalStateException("The connection is gone");
				}

				@Override
				public CompletableFuture<BlockBatch> request(BlockRequest request) {
					throw new IllegalStateException("The connection is gone");
				}
			};
			final Server other = new Server("B", chain);

			rig.hears(broken, new Server("A", chain).status());
			rig.hears(other, other.status());

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldChargeAPeerThatClaimedAheadAndSendsNothingAndAskItNoMoreUntilItSpeaksAgain()
		throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server empty = new Server("E", chain).serving(request -> List.of());

			rig.hears(empty, empty.status());
			rig.tick(DriverRig.TICK);

			assertThat(empty.requests, hasSize(1));
			assertThat(rig.limiter.isPenalised("E"), is(true));
			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(rig.built, equalTo(List.of(false)));
		}
	}

	@Example
	public void shouldChargeAnEmptyBatchFiveAndNotMore() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(scoringSixIsAPenalty())) {
			final Server empty = new Server("E", chain).serving(request -> List.of());

			rig.hears(empty, empty.status());

			assertThat(rig.limiter.isPenalised("E"), is(false));

			rig.limiter.misbehaved("E", 1);

			assertThat(rig.limiter.isPenalised("E"), is(true));
		}
	}

	@Example
	public void shouldBanThePeerWhoseBlockMakesTheNodeThrowReplaceTheNodeOnceAndFinishWithAnotherPeer()
		throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server culprit = new Server("A", chain);
			final Server other = new Server("B", chain);

			rig.breakSchedule();
			rig.hears(culprit, culprit.status());

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.built, equalTo(List.of(false, false)));
			assertThat(culprit.requests, hasSize(1));

			rig.hears(other, other.status());

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.built, equalTo(List.of(false, false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, false, true)));
		}
	}

	@Example
	public void shouldNotChargeAPeerForABatchOfBlocksTheLedgerHasPassed() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server stale = new Server("S", chain).holding().serving(request -> chain.ledger.blocksFrom(1, 2));
			final RecordingPeer live = rig.network.connect("L");

			rig.hears(stale, stale.status());
			rig.driver.onMessage(live, new Decision(chain.block(1)));
			rig.driver.onMessage(live, new Decision(chain.block(2)));
			stale.release();

			assertThat(rig.limiter.isPenalised("S"), is(false));
			assertThat(rig.ledger().height(), equalTo(2L));
		}
	}

	@Example
	public void shouldSyncPastACheckpointThatTheChainMeets() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10);
			DriverRig rig = freshBelow(new Checkpoint(10, chain.block(10).hash()))) {
			final Server server = new Server("S", chain);

			rig.hears(server, server.status());

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldHaltBeforeTheBlockThatIsNotTheCheckpointAndSyncNoMore() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); Chain other = new Chain(BLOCKS, 11);
			DriverRig rig = freshBelow(new Checkpoint(10, other.block(10).hash()))) {
			final Server server = new Server("S", chain);
			final Server later = new Server("T", chain);

			rig.hears(server, server.status());

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Block 10 differs from the trusted checkpoint")));
			assertThat(rig.ledger().height(), equalTo(9L));
			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(server.requests, hasSize(1));

			rig.hears(later, later.status());

			assertThat(later.requests, is(empty()));
		}
	}

	@Example
	public void shouldHaltAtStartWhenTheBlockOnDiskAtTheCheckpointHeightIsAnother() throws IOException {
		try (Chain chain = new Chain(12, 10); Chain other = new Chain(12, 11);
			DriverRig rig = startedOver(chain, 12, new Checkpoint(10, other.block(10).hash()))) {
			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Block 10 differs from the trusted checkpoint")));
			assertThat(rig.ledger().height(), equalTo(12L));
			assertThat(rig.scheduler.pending(), is(empty()));
		}
	}

	@Example
	public void shouldHaltAtStartWhenTheTipOnDiskIsTheCheckpointHeightAndIsAnother() throws IOException {
		try (Chain chain = new Chain(10, 10); Chain other = new Chain(10, 11);
			DriverRig rig = startedOver(chain, 10, new Checkpoint(10, other.block(10).hash()))) {
			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Block 10 differs from the trusted checkpoint")));
		}
	}

	@Example
	public void shouldStartWhenTheBlockOnDiskAtTheCheckpointHeightIsTheCheckpoint() throws IOException {
		try (Chain chain = new Chain(12, 10);
			DriverRig rig = startedOver(chain, 12, new Checkpoint(10, chain.block(10).hash()))) {
			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(12L));
		}
	}

	@Example
	public void shouldStartWhenTheCheckpointHeightIsNotReachedOnDisk() throws IOException {
		try (Chain chain = new Chain(12, 10); Chain other = new Chain(12, 11);
			DriverRig rig = startedOver(chain, 5, new Checkpoint(10, other.block(10).hash()))) {
			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(5L));
		}
	}

	@Example
	public void shouldStartOverBlocksOnDiskWithNoCheckpoint() throws IOException {
		try (Chain chain = new Chain(12, 10); DriverRig rig = startedOver(chain, 12, null)) {
			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(12L));
		}
	}

	@Example
	public void shouldHaltOnADecisionOfTheCheckpointHeightThatIsAnotherBlock() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); Chain other = new Chain(BLOCKS, 11);
			DriverRig rig = freshBelow(new Checkpoint(2, other.block(2).hash()))) {
			final RecordingPeer live = rig.network.connect("L");

			rig.driver.onMessage(live, new Decision(chain.block(1)));
			rig.driver.onMessage(live, new Decision(chain.block(2)));

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Block 2 differs from the trusted checkpoint")));
			assertThat(rig.ledger().height(), equalTo(1L));
		}
	}

	@Example
	public void shouldRefuseAndNotHaltOnABlockOfTheCheckpointHeightThatIsNotCertified() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); Chain other = new Chain(BLOCKS, 11);
			DriverRig rig = freshBelow(new Checkpoint(10, other.block(10).hash()))) {
			final Server server = new Server("S", chain).serving(
				request -> spoiling(chain.ledger.blocksFrom(request.from(), request.max()), 10));

			rig.hears(server, server.status());

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(9L));
			assertThat(rig.limiter.isPenalised("S"), is(true));
		}
	}

	@Example
	public void shouldHaltOnACertifiedBlockOfAHeightItHoldsWithAnotherHashAndGoOnServingWhatItHas()
		throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);
			final RecordingPeer watcher = rig.network.connect("W");
			final RecordingPeer other = rig.network.connect("O");
			final Ballot early = Ballot.signed(Ballot.Type.PREVOTE, seed(3), chain.ledger.chainId(), 4, 0, null);
			final Ballot late = Ballot.signed(Ballot.Type.PREVOTE, seed(4), chain.ledger.chainId(), 4, 0, null);

			rig.hears(server, server.status());
			rig.driver.onMessage(other, early);

			assertThat(watcher.sent(Ballot.class), hasItem(early));

			rig.driver.onMessage(other, new Decision(fork.block(2)));

			final int said = watcher.sent().size();

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Two certified blocks at height 2")));
			assertThat(rig.ledger().height(), equalTo(3L));

			rig.driver.onMessage(other, late);
			rig.driver.onTransaction(other, mint(9, 10));
			rig.hears(other, server.status());

			assertThat(watcher.sent(), hasSize(said));
			assertThat(rig.driver.onBlockRequest(other, new BlockRequest(1, 16)).join().blocks(),
				equalTo(chain.ledger.blocksFrom(1, 16)));
		}
	}

	@Example
	public void shouldHaltOnACertifiedBlockOfTheTipWithAnotherHashToo() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.hears(server, server.status());
			rig.driver.onMessage(rig.network.connect("O"), new Decision(fork.block(3)));

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Two certified blocks at height 3")));
		}
	}

	@Example
	public void shouldNotHaltOnADecisionOfAHeldHeightThatNoQuorumHasCertifiedAndChargeItsSender()
		throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);
			final Block certified = fork.block(2);
			final Endorsement one = certified.getEndorsements().get(0);
			final List<Block> forged = List.of(certifiedBy(certified, List.of(one)),
				certifiedBy(certified, List.of(one, one, one)), spoiled(certified),
				certifiedBy(certified, List.of()), LedgerFixtures.certify(certifiedBy(certified, List.of()),
					certified.getView(), chain.ledger.chainId(), seeds(5, 4)));

			rig.hears(server, server.status());

			for (int n = 0; n < forged.size(); n++) {
				final RecordingPeer sender = rig.network.connect("O" + n);

				rig.driver.onMessage(sender, new Decision(forged.get(n)));

				assertThat(rig.limiter.isPenalised("O" + n), is(true));
			}

			assertThat(rig.driver.halted(), is(false));

			rig.driver.onMessage(rig.network.connect("G"), new Decision(certified));

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.limiter.isPenalised("G"), is(false));
		}
	}

	@Example
	public void shouldHaltWhenACertifiedBlockOfAnotherChainIsTheNextAndFollowsNotTheTip() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain other = new Chain(4, 11);
			DriverRig rig = startedOver(chain, 3, null, TOUCHY)) {
			final RecordingPeer sender = rig.network.connect("O");

			rig.driver.onMessage(sender, new Decision(other.block(4)));

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Chain diverged at 4")));
			assertThat(rig.limiter.isPenalised("O"), is(false));
			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	@Example
	public void shouldHaltInsteadOfBlamingAPeerWhoseBlocksAreOfAnotherChainThanTheTip() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain other = new Chain(8, 11);
			DriverRig rig = startedOver(chain, 3, null, TOUCHY)) {
			final Server server = new Server("B", other);

			rig.hears(server, server.status());

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Chain diverged at 4")));
			assertThat(rig.limiter.isPenalised("B"), is(false));
			assertThat(rig.ledger().height(), equalTo(3L));
			assertThat(server.requests, hasSize(1));
		}
	}

	@Example
	public void shouldNotHaltOnABlockOfAnotherChainThatNoQuorumHasCertified() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain other = new Chain(4, 11);
			DriverRig rig = startedOver(chain, 3, null, DriverRig.STRICT)) {
			final RecordingPeer sender = rig.network.connect("O");

			rig.driver.onMessage(sender, new Decision(spoiled(other.block(4))));

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.limiter.isPenalised("O"), is(true));
		}
	}

	@Example
	public void shouldBlameAPeerWhoseBatchHoldsAnotherUncertifiedBlockAtAHeldHeight() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain other = new Chain(3, 11); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", other).holding().serving(
				request -> spoiling(other.ledger.blocksFrom(request.from(), request.max()), 1));
			final RecordingPeer live = rig.network.connect("L");

			rig.hears(server, server.status());
			rig.driver.onMessage(live, new Decision(chain.block(1)));
			server.release();

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.limiter.isPenalised("S"), is(true));
			assertThat(rig.driver.doubts("S"), is(true));
		}
	}

	@Example
	public void shouldHaltBeforeItSaysAnythingWhenItsOwnDecisionIsNotTheCheckpoint() throws IOException {
		try (Chain other = new Chain(1, 11);
			DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), DriverRig.STRICT, new DirectSerial(),
				trusting(new Checkpoint(1, other.block(1).hash()))).started().promoted()) {
			final RecordingPeer watcher = rig.network.connect("W");

			rig.driver.submit(mint(1, 500)).join();

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Block 1 differs from the trusted checkpoint")));
			assertThat(rig.driver.signing(), is(false));
			assertThat(watcher.sent(Decision.class), is(empty()));
			assertThat(watcher.sent(Status.class), is(empty()));
		}
	}

	@Example
	public void shouldNotHaltWhenItsOwnDecisionIsTheCheckpoint() throws IOException {
		try (DriverRig first = DriverRig.solo()) {
			first.driver.submit(mint(1, 500)).join();

			final byte[] decided = first.ledger().blocksFrom(1, 1).get(0).hash();

			try (DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), DriverRig.STRICT, new DirectSerial(),
				trusting(new Checkpoint(1, decided))).started().promoted()) {
				rig.driver.submit(mint(1, 500)).join();

				assertThat(rig.ledger().height(), equalTo(1L));
				assertThat(rig.driver.halted(), is(false));
			}
		}
	}

	@Example
	public void shouldIgnoreWithoutHaltingTheDecisionsItHoldsAlready() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);
			final RecordingPeer other = rig.network.connect("O");
			final RecordingPeer watcher = rig.network.connect("W");

			rig.hears(server, server.status());

			final int said = watcher.sent().size();

			for (long height = 1; height <= 3; height++) {
				rig.driver.onMessage(other, new Decision(chain.block(height)));
			}

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.limiter.isPenalised("O"), is(false));
			assertThat(watcher.sent(), hasSize(said));
		}
	}

	@Example
	public void shouldHaltWhenABatchBringsACertifiedBlockOfAHeightTheLedgerHasTakenInSinceTheRequest()
		throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); DriverRig rig = fresh()) {
			final Server server = new Server("S", fork).holding();
			final RecordingPeer live = rig.network.connect("L");

			rig.hears(server, server.status());
			rig.driver.onMessage(live, new Decision(chain.block(1)));
			server.release();

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Two certified blocks at height 1")));
			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.ledger().tipHash(), equalTo(chain.block(1).hash()));
		}
	}

	@Example
	public void shouldNotSyncWhileHalted() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); Chain longer = new Chain(BLOCKS, 10);
			DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);
			final Server ahead = new Server("A", longer);

			rig.hears(server, server.status());
			rig.driver.onMessage(rig.network.connect("O"), new Decision(fork.block(2)));
			rig.hears(ahead, ahead.status());
			rig.tick(DriverRig.TICK);

			assertThat(ahead.requests, is(empty()));
			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	@Example
	public void shouldPromoteAfterTheGraceWhenNoPeerHasSpokenAndNotBefore() throws IOException {
		try (DriverRig rig = fresh()) {
			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				hasItem(DriverRig.GRACE));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldPromoteOnlyAfterTheGraceWhenAPeerIsAtTheSameHeight() throws IOException {
		try (Chain chain = new Chain(0, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.hears(server, server.status());

			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(server.requests, is(empty()));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldNotPromoteWhileAPeerIsAheadEvenWhenTheGraceHasPassed() throws IOException {
		try (Chain chain = new Chain(5, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.hears(server, server.status());
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			server.release();

			assertThat(rig.ledger().height(), equalTo(5L));
			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldPromoteOnATickOnceTheDecisionsItWasGivenBringItToTheHeightOfThePeers() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();
			final RecordingPeer live = rig.network.connect("L");

			rig.hears(server, server.status());
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			for (long height = 1; height <= 3; height++) {
				rig.driver.onMessage(live, new Decision(chain.block(height)));
			}

			assertThat(rig.built, equalTo(List.of(false)));

			rig.tick(DriverRig.TICK);

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldNotPromoteOnAStatusThatIsOfAnotherChainAndPenaliseThePeer() throws IOException {
		try (Chain chain = new Chain(5, 10); DriverRig rig = fresh()) {
			final byte[] filled = new byte[Digests.HASH_SIZE];

			Arrays.fill(filled, (byte) 7);

			final Server server = new Server("S", chain);

			rig.hears(server, new Status(filled, 5, filled));

			assertThat(server.requests, is(empty()));
			assertThat(rig.limiter.isPenalised("S"), is(true));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldNeverPromoteAnObserver() throws IOException {
		try (Chain chain = new Chain(0, 10); DriverRig rig = DriverRig.observer()) {
			final Server server = new Server("S", chain);

			rig.passGrace();
			rig.hears(server, server.status());

			assertThat(rig.built, equalTo(List.of(false)));
		}
	}

	@Example
	public void shouldKeepItsVoteWhileItFetchesWhatItLacksAndSignAtTheTip() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();
			final RecordingPeer watcher = rig.network.connect("W");

			rig.passGrace();
			rig.hears(server, server.status());

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(server.requests, hasSize(1));

			server.release();
			rig.tick(DriverRig.HEARTBEAT);

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(server.requests, hasSize(3));
			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(rig.driver.signing(), is(true));
			assertThat(watcher.sent(Heartbeat.class), hasSize(1));
		}
	}

	@Example
	public void shouldKeepSigningThroughASyncThatBringsItToTheTipInOneBatch() throws IOException {
		try (Chain chain = new Chain(9, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.passGrace();
			rig.hears(server, server.status());
			server.release();

			assertThat(rig.ledger().height(), equalTo(9L));
			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldKeepSigningThroughABatchOfOneBlock() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).serving(request -> chain.ledger.blocksFrom(request.from(), 1));

			rig.passGrace();
			rig.hears(server, server.statusAt(3));

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	@Example
	public void shouldNotLetALiarWhoClaimsAheadForEverMoveAnyOfFourSigningNodesOutOfVoting() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig one = DriverRig.four(1, DriverRig.STRICT);
			DriverRig two = DriverRig.four(2, DriverRig.STRICT); DriverRig three = DriverRig.four(3, DriverRig.STRICT);
			DriverRig four = DriverRig.four(4, DriverRig.STRICT)) {
			final List<DriverRig> rigs = List.of(one, two, three, four);
			final List<Server> liars = rigs.stream().map(rig -> new Server("L", chain).holding()).toList();

			for (int interval = 0; interval < 10; interval++) {
				for (int n = 0; n < 4; n++) {
					rigs.get(n).clock.addAndGet(DriverRig.STATUS);
					rigs.get(n).hears(liars.get(n), liars.get(n).statusAt(2));
				}
			}

			for (int n = 0; n < 4; n++) {
				assertThat(rigs.get(n).built, equalTo(List.of(true)));
				assertThat(rigs.get(n).driver.halted(), is(false));
				assertThat(liars.get(n).requests, hasSize(1));
			}
		}
	}

	@Example
	public void shouldNotLetALiarWhoAnswersNothingKeepAFreshNodeFromSigning() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig rig = fresh()) {
			final Server liar = new Server("L", chain).holding();

			rig.hears(liar, liar.statusAt(2));
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			liar.failHeld(new IllegalStateException("The request timed out"));

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(rig.driver.doubts("L"), is(false));

			liar.failHeld(new IllegalStateException("The request timed out"));

			assertThat(rig.driver.doubts("L"), is(true));
		}
	}

	@Example
	public void shouldNotPromoteARestartedValidatorOnALowClaimBeforeTheGraceAndPromoteItWithAnHonestPeerAfter()
		throws IOException {
		try (Chain chain = new Chain(3, 10); Chain bare = new Chain(0, 10);
			DriverRig rig = startedOver(chain, 3, null, TOUCHY)) {
			final Server liar = new Server("L", bare);
			final Server honest = new Server("H", chain);

			rig.hears(liar, liar.status());

			assertThat(rig.built, equalTo(List.of(false)));

			rig.hears(honest, honest.status());

			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldLetManyAddressesHoldTheVoteBackForNoMoreThanOneUnansweredRequest() throws IOException {
		try (Chain chain = new Chain(10, 10); DriverRig rig = fresh()) {
			final List<Server> sybils = IntStream.range(0, 20)
				.mapToObj(n -> new Server("X" + n, chain).holding()).toList();

			sybils.forEach(sybil -> rig.hears(sybil, sybil.statusAt(10)));
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(sybils.stream().filter(Server::hasHeld).count(), equalTo(1L));

			sybils.forEach(Server::failIfHeld);

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(sybils.stream().mapToInt(sybil -> sybil.requests.size()).sum() <= 2, is(true));
		}
	}

	@Example
	public void shouldPromoteAtTheFirstEmptyAnswerAfterTheGraceWhateverElseIsClaimed() throws IOException {
		try (Chain chain = new Chain(5, 10); DriverRig rig = fresh()) {
			final Server empty = new Server("E", chain).holding().emptying();
			final Server other = new Server("O", chain).holding();

			rig.hears(empty, empty.statusAt(3));
			rig.hears(other, other.statusAt(5));
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			empty.release();

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(other.requests, hasSize(1));
		}
	}

	@Example
	public void shouldNotCountTheClaimOfAPeerThatIsNotConnectedAnyMore() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server leaving = new Server("L", chain).holding();

			rig.hears(leaving, leaving.status());
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			rig.network.remove("L");
			rig.tick(DriverRig.TICK);

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldPromoteAfterTheGraceWhenTheOnlyPeerIsDoubtedForFailingRequests() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server silent = new Server("S", chain).failing(new IllegalStateException("The request timed out"));

			rig.hears(silent, silent.status());

			assertThat(rig.driver.doubts("S"), is(true));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	/* Every driver is connected to every other, and each end tells the other where its chain stands */
	private static List<DriverLink> mesh(List<DriverRig> rigs) {
		return mesh(rigs, null, 0);
	}

	private static List<DriverLink> mesh(List<DriverRig> rigs, ManualScheduler lagging, long lagMillis) {
		final List<DriverLink> links = new ArrayList<>();

		for (int i = 0; i < rigs.size(); i++) {
			for (int j = i + 1; j < rigs.size(); j++) {
				final DriverLink[] link = DriverLink.connect(rigs.get(i).driver, "N" + i, rigs.get(j).driver, "N" + j);

				if (lagging != null) {
					link[0].lagging(lagging, lagMillis);
					link[1].lagging(lagging, lagMillis);
				}

				rigs.get(i).network.add(link[0]);
				rigs.get(j).network.add(link[1]);
				rigs.get(i).driver.onPeerConnected(link[0]);
				rigs.get(j).driver.onPeerConnected(link[1]);
				links.addAll(List.of(link));
			}
		}

		return links;
	}

	@Example
	public void shouldPullNoBlockAndCostNoScoreInASteadyStateOfFiveBlocksASecond() throws IOException {
		final AtomicLong clock = new AtomicLong(DriverRig.START);
		final ManualScheduler scheduler = new ManualScheduler(clock);
		final List<DriverRig> rigs = new ArrayList<>();

		try {
			for (int n = 1; n <= 4; n++) {
				rigs.add(new DriverRig(DriverRig.FOUR, seed(n), TOUCHY, new DirectSerial(), DriverRig.OPTIONS, clock,
					scheduler).started());
			}

			final List<DriverLink> links = mesh(rigs, scheduler, 20);

			scheduler.advanceBy(DriverRig.GRACE + 1_000);

			final int before = links.stream().mapToInt(DriverLink::statuses).max().orElse(0);

			for (int n = 1; n <= 50; n++) {
				rigs.get(0).driver.submit(mint(n, 10));
				scheduler.advanceBy(200);
			}

			assertThat(rigs.stream().allMatch(rig -> rig.ledger().height() == 50), is(true));
			assertThat(links.stream().mapToInt(DriverLink::requests).sum(), equalTo(0));
			assertThat(rigs.stream().anyMatch(rig -> rig.limiter.isPenalised("N0") || rig.limiter.isPenalised("N1")
				|| rig.limiter.isPenalised("N2") || rig.limiter.isPenalised("N3")), is(false));
			assertThat(links.stream().mapToInt(DriverLink::statuses).max().orElse(0) - before <= 11, is(true));
		} finally {
			for (final DriverRig rig : rigs) {
				rig.close();
			}
		}
	}

	@Example
	public void shouldSendAStatusOnADecisionNoMoreOftenThanOncePerSecond() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer watcher = rig.network.connect("W");

			for (int n = 1; n <= 11; n++) {
				rig.driver.submit(mint(n, 10)).join();
				rig.clock.addAndGet(200);
			}

			assertThat(rig.ledger().height(), equalTo(11L));
			assertThat(watcher.sent(Status.class), hasSize(3));
		}
	}

	@Example
	public void shouldWaitOneTickBeforeFetchingTheOneBlockAPeerIsAhead() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = startedOver(chain, 1, null, TOUCHY)) {
			final Server one = new Server("O", chain);

			rig.hears(one, one.statusAt(2));

			assertThat(one.requests, is(empty()));
			assertThat(rig.ledger().height(), equalTo(1L));

			rig.tick(DriverRig.TICK);

			assertThat(one.requests, hasSize(1));
			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	@Example
	public void shouldFetchAtOnceFromAPeerTwoBlocksAhead() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = startedOver(chain, 1, null, TOUCHY)) {
			final Server two = new Server("T", chain);

			rig.hears(two, two.statusAt(3));

			assertThat(two.requests, hasSize(1));
			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	@Example
	public void shouldPromoteEveryRestartedValidatorAfterTheGraceAndLetTheNetworkMakeABlock() throws IOException {
		final AtomicLong clock = new AtomicLong(DriverRig.START);
		final ManualScheduler scheduler = new ManualScheduler(clock);
		final List<DriverRig> rigs = new ArrayList<>();

		try {
			for (int n = 1; n <= 4; n++) {
				rigs.add(new DriverRig(DriverRig.FOUR, seed(n), lenient(), new DirectSerial(), DriverRig.OPTIONS,
					clock, scheduler).started());
			}

			mesh(rigs);
			scheduler.advanceBy(DriverRig.GRACE - 1_000);

			assertThat(rigs.stream().map(rig -> rig.built).toList(), equalTo(List.of(List.of(false), List.of(false),
				List.of(false), List.of(false))));

			scheduler.advanceBy(2_000);

			assertThat(rigs.stream().allMatch(rig -> rig.built.equals(List.of(false, true))), is(true));

			rigs.get(0).driver.submit(mint(1, 500));

			for (int step = 0; step < 100 && rigs.stream().anyMatch(rig -> rig.ledger().height() < 1); step++) {
				scheduler.advanceBy(100);
			}

			assertThat(rigs.stream().allMatch(rig -> rig.ledger().height() >= 1), is(true));
			assertThat(rigs.stream().map(rig -> HexFormat.of().formatHex(rig.ledger().blocksFrom(1, 1).get(0).hash()))
				.distinct().count(),
				equalTo(1L));
		} finally {
			for (final DriverRig rig : rigs) {
				rig.close();
			}
		}
	}

	@Example
	public void shouldNotLetALiarWhoAnswersEmptyKeepAFreshNodeFromSigning() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig rig = fresh()) {
			final Server liar = new Server("L", chain).serving(request -> List.of());

			rig.hears(liar, liar.statusAt(2));

			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(liar.requests, hasSize(1));
		}
	}

	@Example
	public void shouldNotCountAClaimThatIsNotRepeatedForTwoStatusIntervalsAndNotBefore() throws IOException {
		try (Chain ahead = new Chain(5, 10); DriverRig rig = fresh()) {
			final Server silent = new Server("S", ahead).holding();

			rig.hears(silent, silent.status());
			rig.passGrace();
			rig.tick(2 * DriverRig.STATUS - 1);

			assertThat(rig.built, equalTo(List.of(false)));

			rig.tick(1);

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldCountAClaimForAnotherTwoIntervalsWhenItIsRepeated() throws IOException {
		try (Chain ahead = new Chain(5, 10); DriverRig rig = fresh()) {
			final Server repeating = new Server("S", ahead).holding();

			rig.hears(repeating, repeating.status());
			rig.passGrace();
			rig.clock.addAndGet(2 * DriverRig.STATUS - 1);
			rig.hears(repeating, repeating.status());
			rig.tick(2 * DriverRig.STATUS - 1);

			assertThat(rig.built, equalTo(List.of(false)));

			rig.tick(1);

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldIgnoreAPeerThatCouldNotBackItsClaimForTenMinutesAndNotLonger() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server liar = new Server("L", chain).serving(request -> List.of());

			rig.hears(liar, liar.statusAt(2));

			assertIgnoredForTenMinutesAndNotLonger(rig, liar, liar.statusAt(2));
		}
	}

	private static void assertIgnoredForTenMinutesAndNotLonger(DriverRig rig, Server liar, Status claim) {
		final int asked = liar.requests.size();

		rig.clock.addAndGet(SyncState.MEMORY_MILLIS - 1);
		rig.hears(liar, claim);

		assertThat(liar.requests, hasSize(asked));

		rig.clock.addAndGet(1);
		rig.hears(liar, claim);

		assertThat(liar.requests, hasSize(asked + 1));
	}

	@Example
	public void shouldDoubtAPeerWhoseRequestFailsTwiceForOneStatusIntervalAndTwiceThatTheNextTime()
		throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server silent = new Server("S", chain).failing(new IllegalStateException("The request timed out"));

			rig.hears(silent, silent.statusAt(2));

			assertThat(silent.requests, hasSize(2));
			assertThat(rig.driver.doubts("S"), is(true));
			assertThat(rig.limiter.isPenalised("S"), is(false));

			rig.clock.addAndGet(DriverRig.STATUS - 1);

			assertThat(rig.driver.doubts("S"), is(true));

			rig.clock.addAndGet(1);

			assertThat(rig.driver.doubts("S"), is(false));

			rig.hears(silent, silent.statusAt(2));
			rig.clock.addAndGet(2 * DriverRig.STATUS - 1);

			assertThat(silent.requests, hasSize(4));
			assertThat(rig.driver.doubts("S"), is(true));

			rig.clock.addAndGet(1);

			assertThat(rig.driver.doubts("S"), is(false));
		}
	}

	@Example
	public void shouldAskAgainOnceAfterAWaitWhenARequestFailsAndNotDoubtThePeerThatThenAnswers() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain).failingNext(1);

			rig.scheduler.runAtOnce(Syncer.PACE_MILLIS);
			rig.hears(server, server.status());

			assertThat(server.requests, hasSize(1));
			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				hasItem(Syncer.RETRY_MILLIS));
			assertThat(rig.driver.doubts("S"), is(false));

			rig.timers().stream().filter(timer -> timer.delayMillis() == Syncer.RETRY_MILLIS)
				.forEach(ManualScheduler.Timer::fire);

			assertThat(server.requests.get(1), equalTo(server.requests.get(0)));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.driver.doubts("S"), is(false));
			assertThat(rig.limiter.isPenalised("S"), is(false));
		}
	}

	private static ManualScheduler.Timer requestTimeout(DriverRig rig) {
		return rig.timers().stream().filter(timer -> timer.delayMillis() == DriverRig.OPTIONS.requestTimeoutMillis())
			.findFirst().orElseThrow();
	}

	@Example
	public void shouldCountARequestThatGetsNoAnswerInTheRequestTimeoutAsFailedAndAskTheNextClaimantAtOnce()
		throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server silent = new Server("S", chain).holding();
			final Server honest = new Server("H", chain);

			rig.hears(silent, silent.statusAt(2));
			rig.hears(honest, honest.status());

			assertThat(silent.requests, hasSize(1));
			assertThat(honest.requests, is(empty()));

			requestTimeout(rig).fire();

			assertThat(silent.requests, hasSize(1));
			assertThat(rig.driver.doubts("S"), is(true));
			assertThat(honest.requests, is(not(empty())));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.limiter.isPenalised("S"), is(false));
		}
	}

	@Example
	public void shouldDoubtThePeerThatTimedOutForOneStatusIntervalAndTwiceThatTheNextTime() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server silent = new Server("S", chain).holding();

			rig.hears(silent, silent.statusAt(2));
			requestTimeout(rig).fire();
			rig.clock.addAndGet(DriverRig.STATUS - 1);

			assertThat(rig.driver.doubts("S"), is(true));

			rig.clock.addAndGet(1);

			assertThat(rig.driver.doubts("S"), is(false));

			rig.hears(silent, silent.statusAt(2));
			requestTimeout(rig).fire();
			rig.clock.addAndGet(2 * DriverRig.STATUS - 1);

			assertThat(rig.driver.doubts("S"), is(true));

			rig.clock.addAndGet(1);

			assertThat(rig.driver.doubts("S"), is(false));
		}
	}

	@Example
	public void shouldStartTheDoubtOverAfterABatchWasDeliveredWhichIsAFailureRowEnded() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server server = new Server("S", chain).holding();

			rig.hears(server, server.statusAt(2));
			requestTimeout(rig).fire();
			rig.clock.addAndGet(DriverRig.STATUS);
			server.holding = false;
			rig.hears(server, server.statusAt(20));

			assertThat(rig.ledger().height(), equalTo(32L));

			server.holding = true;
			rig.hears(server, server.status());
			requestTimeout(rig).fire();
			rig.clock.addAndGet(DriverRig.STATUS - 1);

			assertThat(rig.driver.doubts("S"), is(true));

			rig.clock.addAndGet(1);

			assertThat(rig.driver.doubts("S"), is(false));
		}
	}

	@Example
	public void shouldIgnoreTheLateAnswerOfARequestThatTimedOut() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server silent = new Server("S", chain).holding();
			final Server honest = new Server("H", chain).holding();

			rig.hears(silent, silent.statusAt(2));
			rig.hears(honest, honest.status());
			requestTimeout(rig).fire();

			assertThat(honest.requests, hasSize(1));

			silent.release();

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(honest.requests, hasSize(1));
			assertThat(rig.driver.syncing(), is(true));

			honest.release();

			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldLeaveNoTimerBehindWhenTheAnswerComesInTime() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server slow = new Server("S", chain).holding();

			rig.hears(slow, slow.status());

			final ManualScheduler.Timer timer = requestTimeout(rig);

			slow.release();

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(timer.cancelled(), is(true));
			assertThat(rig.driver.doubts("S"), is(false));
		}
	}

	@Example
	public void shouldReachTheHonestPeerThroughFortySilentClaimantsWithinFortyTimeouts() throws IOException {
		final AtomicLong clock = new AtomicLong(DriverRig.START);
		final ManualScheduler scheduler = new ManualScheduler(clock);
		final long timeout = DriverRig.OPTIONS.requestTimeoutMillis();

		try (Chain chain = new Chain(BLOCKS, 10);
			DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), burstOf(Kind.STATUS, 100), new DirectSerial(),
				slowStatuses(), clock, scheduler).started()) {
			final List<Server> silent = IntStream.range(0, 40)
				.mapToObj(n -> new Server("S" + n, chain).holding()).toList();
			final Server honest = new Server("H", chain);

			silent.forEach(server -> rig.hears(server, server.statusAt(2)));
			rig.hears(honest, honest.status());

			scheduler.advanceBy(40 * timeout);
			scheduler.advanceBy(4 * Syncer.PACE_MILLIS);

			assertThat(honest.requests, is(not(empty())));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(silent.stream().allMatch(server -> server.requests.size() <= 1), is(true));
		}
	}

	/* The peer that is heard first is asked first, so one that holds the sync while the others are heard is how a test
	   makes the driver choose between them */
	@Example
	public void shouldAskAPeerWeDialledBeforeSilentPeersWhateverTheyClaim() throws IOException {
		final List<String> byHash = askingOrder(List.of("L", "H", "D"), List.of(11L, 11L, 11L), DriverRig.SALT);

		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server decoy = new Server("X", chain).holding();
			final Server low = new Server(byHash.get(0), chain).holding();
			final Server high = new Server(byHash.get(1), chain).holding();
			final Server dialled = new Server(byHash.get(2), chain).dialledByUs();

			rig.hears(decoy, decoy.statusAt(2));
			rig.hears(low, low.statusAt(2));
			rig.hears(high, claim(chain, 1_000_000));
			rig.hears(dialled, dialled.status());
			requestTimeout(rig).fire();

			assertThat(dialled.requests, is(not(empty())));
			assertThat(low.requests, is(empty()));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(high.requests.stream().allMatch(request -> request.from() == TIP + 1), is(true));
		}
	}

	@Example
	public void shouldAskAPeerThatDeliveredBeforeBeforeAnotherThatTheHashWouldPick() throws IOException {
		final SyncState probe = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		probe.heard(new Server("P", null), 11, 0);
		probe.heard(new Server("Q", null), 11, 0);

		final String hashFirst = probe.firstAbove(0, 0).orElseThrow().peer().id();

		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server decoy = new Server("X", chain).holding();
			final Server known = new Server("P".equals(hashFirst) ? "Q" : "P", chain).holding();
			final Server newcomer = new Server(hashFirst, chain);

			rig.hears(known, known.statusAt(20));
			known.releaseIfHeld();
			requestTimeout(rig).fire();
			rig.clock.addAndGet(DriverRig.STATUS);
			known.holding = false;

			assertThat(rig.ledger().height(), equalTo(16L));

			rig.hears(decoy, decoy.statusAt(24));
			rig.hears(newcomer, newcomer.status());
			rig.hears(known, known.status());
			requestTimeout(rig).fire();

			assertThat(newcomer.requests, is(empty()));
			assertThat(known.requests.size(), equalTo(4));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	/* The claims stand for the whole of the test, which runs for two minutes of virtual time */
	private static DriverOptions slowStatuses() {
		return new DriverOptions(DriverRig.TICK, DriverRig.HEARTBEAT, 20 * DriverRig.STATUS, DriverRig.GRACE,
			DriverRig.RESTART_WINDOW, DriverRig.QUEUE, DriverRig.PER_PEER, Optional.empty());
	}

	/* What a peer claims decides whether it is asked at all, and nothing about when */
	@Property(tries = 50)
	public void shouldAskAPeerThatWasDialledBeforeAnyOtherWhateverTheyClaim(
		@ForAll @IntRange(min = 2, max = 8) int peers, @ForAll @IntRange(min = 0, max = 7) int pick,
		@ForAll @Size(min = 8, max = 8) List<@IntRange(min = 1, max = 1_000_000) Integer> claims) {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		for (int n = 0; n < peers; n++) {
			final Server server = new Server("P" + n, null);

			state.heard(n == pick % peers ? server.dialledByUs() : server, claims.get(n), 0);
		}

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("P" + pick % peers));
	}

	@Property(tries = 50)
	public void shouldAskThePeerThatDeliveredLastBeforeThoseThatDeliveredEarlierOrNeverWhateverTheyClaim(
		@ForAll @IntRange(min = 2, max = 8) int peers, @ForAll @IntRange(min = 0, max = 7) int one,
		@ForAll @Size(min = 8, max = 8) List<@IntRange(min = 1, max = 1_000_000) Integer> claims) {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);
		final int earlier = one % peers;
		final int later = (earlier + 1) % peers;

		for (int n = 0; n < peers; n++) {
			state.heard(new Server("P" + n, null), claims.get(n), 0);
		}

		state.delivered("P" + earlier);
		state.delivered("P" + later);

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("P" + later));

		state.delivered("P" + earlier);

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("P" + earlier));
	}

	@Example
	public void shouldAskThePeerWeDialledBeforeThoseThatDeliveredBeforeThoseThatNeverDidWhateverTheyClaim() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		state.heard(new Server("A", null), 10, 0);
		state.heard(new Server("B", null), 1_000_000, 0);
		state.heard(new Server("C", null).dialledByUs(), 1_000_001, 0);
		state.heard(new Server("D", null), 1, 0);
		state.delivered("A");
		state.delivered("B");

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("C"));

		state.drop("C");

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("B"));

		state.drop("B");

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("A"));

		state.drop("A");

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("D"));
	}

	@Example
	public void shouldNotAskAPeerBecauseItClaimsLittleOrBecauseItClaimsAGreatDeal() {
		final List<String> ids = IntStream.range(0, 12).mapToObj(n -> "10.0.0." + n).toList();
		final List<Long> small = Collections.nCopies(12, 11L);
		final List<Long> spread = LongStream.range(0, 12).map(n -> n % 2 == 0 ? 11 + n : 1_000_000 - n).boxed().toList();

		assertThat(askingOrder(ids, small, DriverRig.SALT), equalTo(askingOrder(ids, spread, DriverRig.SALT)));
		assertThat(askingOrder(ids, small, DriverRig.SALT), equalTo(askingOrder(ids, small.reversed(),
			DriverRig.SALT)));
	}

	@Example
	public void shouldAskPeersWithNothingToTellThemApartInTheOrderOfTheirSaltedHashAndTheSameOrderEachTime() {
		final List<String> ids = IntStream.range(0, 12).mapToObj(n -> "10.0.0." + n).toList();
		final List<Long> claims = Collections.nCopies(12, 11L);

		assertThat(askingOrder(ids, claims, DriverRig.SALT), equalTo(askingOrder(ids.reversed(), claims,
			DriverRig.SALT)));
		assertThat(askingOrder(ids, claims, DriverRig.SALT), not(equalTo(askingOrder(ids, claims, 4_001L))));
		assertThat(askingOrder(ids, claims, 4_001L), equalTo(askingOrder(ids, claims, 4_001L)));
	}

	@Example
	public void shouldNotLetAnIdGroundToSortFirstWithoutASaltSortFirstWithAnother() {
		final List<String> ids = IntStream.range(0, 2_000).mapToObj(n -> "10.0." + n / 250 + "." + n % 250).toList();
		final List<Long> claims = Collections.nCopies(2_000, 11L);
		final String ground = askingOrder(ids, claims, 0L).get(0);

		assertThat(askingOrder(ids, claims, 1_234_567L).get(0), not(equalTo(ground)));
		assertThat(askingOrder(ids, claims, -987_654_321L).get(0), not(equalTo(ground)));
		assertThat(askingOrder(ids, claims, 1_234_567L).get(0), not(equalTo(askingOrder(ids, claims,
			-987_654_321L).get(0))));
	}

	/* The ids in the order that a state with that salt would ask them, one after the other */
	private static List<String> askingOrder(List<String> ids, List<Long> claims, long salt) {
		final SyncState state = new SyncState(1_000, EVERYONE, salt);
		final List<String> order = new ArrayList<>();

		for (int n = 0; n < ids.size(); n++) {
			state.heard(new Server(ids.get(n), null), claims.get(n), 0);
		}

		for (Optional<SyncState.Claim> next = state.firstAbove(0, 0); next.isPresent();
			next = state.firstAbove(0, 0)) {
			order.add(next.get().peer().id());
			state.drop(next.get().peer().id());
		}

		return order;
	}

	@Example
	public void shouldFollowTheLatestDeliveryWhicheverOfTwoPeersTheHashWouldPick() {
		final SyncState first = new SyncState(1_000, EVERYONE, DriverRig.SALT);
		final SyncState second = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		Stream.of(first, second).forEach(state -> {
			state.heard(new Server("A", null), 10, 0);
			state.heard(new Server("B", null), 10, 0);
		});
		first.delivered("A");
		second.delivered("B");

		assertThat(first.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("A"));
		assertThat(second.firstAbove(0, 0).orElseThrow().peer().id(), equalTo("B"));
	}

	@Example
	public void shouldNotRememberDeliveriesOnceTheStateIsCleared() {
		final SyncState untouched = new SyncState(1_000, EVERYONE, DriverRig.SALT);
		final SyncState cleared = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		untouched.heard(new Server("A", null), 10, 0);
		untouched.heard(new Server("B", null), 10, 0);

		final String byHash = untouched.firstAbove(0, 0).orElseThrow().peer().id();

		cleared.delivered("A".equals(byHash) ? "B" : "A");
		cleared.clear();
		cleared.heard(new Server("A", null), 10, 0);
		cleared.heard(new Server("B", null), 10, 0);

		assertThat(cleared.firstAbove(0, 0).orElseThrow().peer().id(), equalTo(byHash));
	}

	@Example
	public void shouldKeepAtMostSoManyDeliveriesAndForgetTheOldest() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);
		final List<String> byHash = askingOrder(List.of("old", "new"), List.of(11L, 11L), DriverRig.SALT);
		final String oldest = byHash.get(1);
		final String other = byHash.get(0);

		state.delivered(oldest);

		for (int n = 0; n < SyncState.MAX_CLAIMS; n++) {
			state.delivered("P" + n);
		}

		state.heard(new Server(oldest, null), 11, 0);
		state.heard(new Server(other, null), 11, 0);

		assertThat(state.firstAbove(0, 0).orElseThrow().peer().id(), equalTo(other));
	}

	@Example
	public void shouldWaitAPaceBetweenTwoRequestsOfASyncButNotBeforeTheFirst() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);

			rig.scheduler.runAtOnce();
			rig.hears(server, server.status());

			assertThat(server.requests, hasSize(1));
			assertThat(rig.ledger().height(), equalTo(16L));
			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				hasItem(Syncer.PACE_MILLIS));

			rig.timers().stream().filter(timer -> timer.delayMillis() == Syncer.PACE_MILLIS)
				.forEach(ManualScheduler.Timer::fire);

			assertThat(server.requests, hasSize(2));
			assertThat(rig.ledger().height(), equalTo(32L));
		}
	}

	@Example
	public void shouldLetATickRunBetweenTheBlocksOfABatch() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (Chain chain = new Chain(BLOCKS, 10);
			DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), DriverRig.STRICT, serial).started()) {
			final Server server = new Server("S", chain).holding();
			final List<Long> heights = new ArrayList<>();

			serial.drain();
			rig.hears(server, server.status());
			serial.drain();
			rig.scheduler.listen(delay -> {
				if (delay == DriverRig.TICK) {
					heights.add(rig.ledger().height());
				}
			});
			server.release();
			rig.ticker().fire();
			serial.drain();

			assertThat(heights, equalTo(List.of(1L)));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldSyncSevenHundredBlocksFromAServerThatLimitsWhatItAnswersAndNeverDoubtIt() throws IOException {
		final AtomicLong clock = new AtomicLong(DriverRig.START);
		final ManualScheduler scheduler = new ManualScheduler(clock);

		try (Chain chain = new Chain(700, 10);
			DriverRig server = withBlocks(new DriverRig(DriverRig.FOUR, null, TOUCHY, new DirectSerial(),
				DriverRig.OPTIONS, clock, scheduler), chain, 700).started();
			DriverRig joiner = new DriverRig(DriverRig.FOUR, null, DriverRig.STRICT, new DirectSerial(),
				DriverRig.OPTIONS, clock, scheduler).started()) {
			final DriverLink[] link = DriverLink.connect(joiner.driver, "J", server.driver, "S");

			joiner.hears(link[0], server.ledger().status());

			for (int step = 0; step < 600 && joiner.ledger().height() < 700; step++) {
				scheduler.advanceBy(100);
			}

			assertThat(joiner.ledger().height(), equalTo(700L));
			assertThat(joiner.ledger().tipHash(), equalTo(chain.ledger.tipHash()));
			assertThat(server.limiter.isPenalised("J"), is(false));
			assertThat(joiner.driver.doubts("S"), is(false));
			assertThat(clock.get() - DriverRig.START >= 43 * Syncer.PACE_MILLIS, is(true));
		}
	}

	@Example
	public void shouldIgnoreAPeerWhoseBlockWasRefusedForTenMinutes() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server liar = new Server("L", chain).serving(
				request -> spoiling(chain.ledger.blocksFrom(request.from(), request.max()), 3));

			rig.hears(liar, liar.status());

			assertThat(rig.ledger().height(), equalTo(2L));

			assertIgnoredForTenMinutesAndNotLonger(rig, liar, liar.status());
		}
	}

	@Example
	public void shouldNotCountAStatusWhoseTipIsNotTheBlockItHoldsAtThatHeightAndCostThePeerNothing()
		throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11);
			DriverRig rig = startedOver(chain, 3, null, TOUCHY)) {
			final Server other = new Server("F", fork);
			final Server honest = new Server("H", chain);

			rig.hears(other, other.status());

			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(rig.limiter.isPenalised("F"), is(false));
			assertThat(other.requests, is(empty()));

			rig.hears(honest, honest.status());

			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldSignHeartbeatsOnlyOnceItIsPromoted() throws IOException {
		try (DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), DriverRig.STRICT, new DirectSerial()).started()) {
			final RecordingPeer watcher = rig.network.connect("W");

			rig.tick(DriverRig.HEARTBEAT);

			assertThat(watcher.sent(Heartbeat.class), is(empty()));

			rig.passGrace();
			rig.tick(DriverRig.HEARTBEAT);

			assertThat(watcher.sent(Heartbeat.class), hasSize(1));
		}
	}

	@Example
	public void shouldServeBlocksFromAnyHeightUpToTheTipInBatchesOfAtMostSixteen() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);
			final RecordingPeer asker = rig.network.connect("Q");

			rig.hears(server, server.status());

			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(1, 16)).join().blocks(),
				equalTo(chain.ledger.blocksFrom(1, 16)));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(39, 16)).join().blocks(),
				equalTo(chain.ledger.blocksFrom(39, 16)));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(39, 16)).join().blocks(), hasSize(2));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(41, 16)).join().blocks(), is(empty()));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(Long.MAX_VALUE, 16)).join().blocks(),
				is(empty()));
		}
	}

	@Example
	public void shouldAnswerABlockRequestWithNothingBeforeItHasAnyBlock() throws IOException {
		try (DriverRig rig = fresh()) {
			assertThat(rig.driver.onBlockRequest(rig.network.connect("Q"), new BlockRequest(1, 16)).join().blocks(),
				is(empty()));
		}
	}

	@Example
	public void shouldAnswerOnlyAsManyBlockRequestsAsTheBucketOfAPeerHolds() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2),
			burstOf(Kind.BLOCK_REQUEST, 5), new DirectSerial()).started()) {
			final Server server = new Server("S", chain);
			final RecordingPeer asker = rig.network.connect("Q");

			rig.hears(server, server.status());

			final List<CompletableFuture<BlockBatch>> answers = new ArrayList<>();

			for (int sent = 0; sent < 20; sent++) {
				answers.add(rig.driver.onBlockRequest(asker, new BlockRequest(1, 16)));
			}

			assertThat(answers.stream().filter(CompletableFuture::isCompletedExceptionally).count(), equalTo(15L));
			assertThat(answers.stream().filter(answer -> answer.isDone() && !answer.isCompletedExceptionally())
				.count(), equalTo(5L));
		}
	}

	@Example
	public void shouldSyncALongChainFromOnePeerInOneRunWithoutAnyScoreOnIt() throws IOException {
		try (Chain chain = new Chain(200, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);

			rig.hears(server, server.status());

			assertThat(rig.ledger().height(), equalTo(200L));
			assertThat(rig.ledger().tipHash(), equalTo(chain.ledger.tipHash()));
			assertThat(server.requests, hasSize(13));
			assertThat(rig.limiter.isPenalised("S"), is(false));
			assertThat(rig.built, equalTo(List.of(false)));
		}
	}

	@Example
	public void shouldSayWhereItStandsToAPeerThatConnects() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.hears(server, server.status());

			final RecordingPeer newcomer = rig.network.connect("N");

			rig.driver.onPeerConnected(newcomer);

			assertThat(newcomer.sent(Status.class), equalTo(List.of(rig.ledger().status())));
		}
	}

	private static List<LedgerNode> live(DriverRig rig) {
		return rig.nodes.stream().filter(LedgerNode::usable).toList();
	}

	/* What holds after any event: at most one node is alive, the driver builds its node to vote exactly when the node
	   alive does, a halted driver holds no node, no sync and no vote, and a sync that runs has a request out */
	private static void assertFailsClosed(DriverRig rig, List<Server> servers) {
		final List<LedgerNode> alive = live(rig);

		assertThat(alive.size() <= 1, is(true));

		if (rig.scheduler.pending().stream().anyMatch(timer -> timer.delayMillis() == DriverRig.GRACE)) {
			assertThat(rig.driver.signing(), is(false));
		}

		if (rig.driver.halted()) {
			assertThat(alive, is(empty()));
			assertThat(rig.driver.signing(), is(false));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.driver.awaiting(), is(false));
		} else {
			alive.forEach(node -> assertThat(rig.driver.signing(), equalTo(node.signs())));
			assertThat(!rig.driver.syncing() || rig.driver.awaiting() || waiting(rig), is(true));
		}
	}

	/* Whether a sync waits for a pace or a retry to pass */
	private static boolean waiting(DriverRig rig) {
		return rig.timers().stream().anyMatch(timer -> timer.delayMillis() == Syncer.PACE_MILLIS
			|| timer.delayMillis() == Syncer.RETRY_MILLIS);
	}

	@Example
	public void shouldHaltWithNoNodeAndNoVoteWhenNeitherTheNodeToVoteNorTheObserverCanBeBuilt() throws IOException {
		try (DriverRig rig = fresh()) {
			final RecordingPeer watcher = rig.network.connect("W");

			rig.failCreation(new IOException("The files are gone"));
			rig.passGrace();
			rig.tick(DriverRig.HEARTBEAT);

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), is(empty()));
			assertThat(rig.built, equalTo(List.of(false, true, false)));
			assertThat(watcher.sent(Heartbeat.class), is(empty()));
			assertFailsClosed(rig, List.of());
		}
	}

	@Example
	public void shouldHaltWithNoNodeWhenEvenTheObserverThatTakesTheNodeToVotePlaceCannotStart() throws IOException {
		try (DriverRig rig = fresh()) {
			rig.breakNewNodes();
			rig.passGrace();

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), is(empty()));
			assertThat(rig.built, equalTo(List.of(false, true, false, false)));
			assertFailsClosed(rig, List.of());
		}
	}

	@Example
	public void shouldGoOnFollowingAndTryAgainWithALongerWaitEachTimeWhenTheNodeToVoteCannotBeBuilt()
		throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.failCreationOfVoters(new IOException("The sign state is locked"));
			rig.passGrace();

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), hasSize(1));
			assertThat(rig.built, equalTo(List.of(false, true, false)));

			rig.hears(server, server.status());

			assertThat(rig.ledger().height(), equalTo(3L));

			for (int tick = 0; tick < 10; tick++) {
				rig.tick(DriverRig.TICK);
			}

			assertThat(rig.built.stream().filter(votes -> votes).count(), equalTo(4L));
			assertThat(rig.driver.halted(), is(false));

			rig.mendVoters();

			for (int tick = 0; tick < 30 && !rig.driver.signing(); tick++) {
				rig.tick(DriverRig.TICK);
			}

			assertThat(rig.driver.signing(), is(true));
			assertThat(live(rig), hasSize(1));
			assertFailsClosed(rig, List.of(server));
		}
	}

	@Example
	public void shouldGoOnFollowingWhenTheNodeToVoteBuildsAndCannotStart() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.breakVoters();
			rig.passGrace();

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), hasSize(1));
			assertThat(rig.built, equalTo(List.of(false, true, false)));

			rig.hears(server, server.status());

			assertThat(rig.ledger().height(), equalTo(3L));
			assertFailsClosed(rig, List.of(server));
		}
	}

	@Example
	public void shouldKeepTheFirstReasonOfAHaltAndStayClosedWhenAHaltComesAfterTheClose() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = startedOver(chain, 3, null)) {
			final RecordingPeer asker = rig.network.connect("Q");

			rig.driver.halt("first");
			rig.driver.halt("second");

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("first")));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(1, 16)).join().blocks(), hasSize(3));

			rig.driver.close();
			rig.driver.halt("late");

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("first")));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(1, 16)).isCompletedExceptionally(),
				is(true));
		}
	}

	@Example
	public void shouldStayClosedWhenAHaltComesAfterTheCloseOfADriverThatNeverHalted() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = startedOver(chain, 3, null)) {
			final RecordingPeer asker = rig.network.connect("Q");

			rig.driver.close();
			rig.driver.halt("late");

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("late")));
			assertThat(rig.driver.onBlockRequest(asker, new BlockRequest(1, 16)).isCompletedExceptionally(),
				is(true));
		}
	}

	@Example
	public void shouldHaltWhenTheFactoryBuildsANodeThatVotesAndWasNotAskedToVote() throws IOException {
		try (DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), DriverRig.STRICT, new DirectSerial())) {
			rig.buildAlways(true);
			rig.driver.start();

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), is(empty()));
		}
	}

	@Example
	public void shouldGoOnFollowingWhenTheFactoryBuildsAnObserverForAPromotion() throws IOException {
		try (DriverRig rig = fresh()) {
			rig.buildAlways(false);
			rig.passGrace();

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), hasSize(1));
			assertThat(rig.built, equalTo(List.of(false, true, false)));
		}
	}

	@Example
	public void shouldFinishASyncOnTheNodeThatReplacedTheOneItStartedOn() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();
			final RecordingPeer a = rig.network.connect("A");

			rig.passGrace();
			rig.hears(server, server.status());
			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(a, rig.proposal());

			assertThat(rig.built, equalTo(List.of(false, true, true)));
			assertThat(rig.driver.syncing(), is(true));
			assertFailsClosed(rig, List.of(server));

			server.release();

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.driver.claimedAbove("S"), is(false));
			assertFailsClosed(rig, List.of(server));
			assertThat(live(rig), hasSize(1));
			assertThat(live(rig).get(0).height(), equalTo(TIP + 1));
		}
	}

	@Example
	public void shouldPromoteTheReplacementWhenTheNodeWasReplacedBetweenTheStatusAndThePromotion()
		throws IOException {
		try (Chain chain = new Chain(5, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.hears(server, server.status());
			rig.breakNode();
			rig.tick(DriverRig.TICK);

			assertThat(rig.built, equalTo(List.of(false, false)));
			assertThat(rig.driver.signing(), is(false));

			server.failHeld(new IllegalStateException("The request timed out"));
			server.failHeld(new IllegalStateException("The request timed out"));
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, false, true)));
			assertThat(rig.driver.signing(), is(true));
			assertThat(live(rig), hasSize(1));
			assertThat(live(rig).get(0), is(rig.nodes.get(2)));
			assertThat(rig.nodes.get(2).signs(), is(true));
			assertFailsClosed(rig, List.of(server));
		}
	}

	@Example
	public void shouldEndTheSyncAndKeepOneNodeWhenABlockMakesTheNodeThrowInTheApplyLoop() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server culprit = new Server("A", chain);
			final Server other = new Server("B", chain);

			rig.breakSchedule();
			rig.hears(culprit, culprit.status());

			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.driver.claimedAbove("A"), is(false));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), hasSize(1));
			assertFailsClosed(rig, List.of(culprit, other));

			rig.hears(other, other.status());

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.driver.syncing(), is(false));
			assertFailsClosed(rig, List.of(culprit, other));
		}
	}

	@Example
	public void shouldHaltWithNoSyncAndNoVoteWhenSomethingBeyondTheNodeThrowsAfterTheApplyLoop() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.hears(server, server.status());
			rig.network.refuse(new IllegalStateException("The network failed"));
			server.release();

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.ledger().height(), equalTo(3L));
			assertFailsClosed(rig, List.of(server));
		}
	}

	@Example
	public void shouldDropTheSyncAndHoldNoNodeWhenTheDriverHaltsWithARequestOut() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();
			final RecordingPeer a = rig.network.connect("A");

			rig.passGrace();
			rig.hears(server, server.status());

			assertThat(rig.driver.syncing(), is(true));

			rig.network.refuse(new IllegalStateException("The network failed"));
			rig.driver.onTransaction(a, mint(1, 500));

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.driver.claimedAbove("S"), is(false));
			assertThat(rig.driver.signing(), is(false));
			assertThat(live(rig), is(empty()));

			server.release();

			assertThat(server.requests, hasSize(1));
			assertThat(rig.driver.syncing(), is(false));
		}
	}

	@Example
	public void shouldApplyNothingAndBlameNoOneForABatchThatComesAfterTheClose() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain).holding();

			rig.hears(server, server.status());
			rig.driver.close();
			server.release();

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(rig.driver.doubts("S"), is(false));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.scheduler.pending(), is(empty()));
		}
	}

	@Example
	public void shouldStartNoWaitAndMarkNoOneForARequestThatFailsAfterTheDriverHalted() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain).holding();

			rig.scheduler.runAtOnce();
			rig.hears(server, server.status());
			rig.driver.halt("stopped");
			server.failHeld(new IllegalStateException("The request timed out"));

			assertThat(server.requests, hasSize(1));
			assertThat(rig.driver.doubts("S"), is(false));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.scheduler.pending(), is(empty()));
		}
	}

	@Example
	public void shouldHaveNothingWaitingWhenTheVoteThatAnUnansweredRequestAsksForHaltsTheDriver()
		throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.scheduler.runAtOnce();
			rig.hears(server, server.status());
			rig.passGrace();
			rig.failCreation(new IOException("The files are gone"));
			server.failHeld(new IllegalStateException("The request timed out"));

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.scheduler.pending(), is(empty()));
			assertThat(server.requests, hasSize(1));
			assertFailsClosed(rig, List.of(server));
		}
	}

	@Example
	public void shouldApplyNoMoreBlocksOnceTheDriverHasStoppedBetweenTwoOfThem() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (Chain chain = new Chain(BLOCKS, 10);
			DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), TOUCHY, serial).started()) {
			final Server server = new Server("S", chain).holding();

			serial.drain();
			rig.hears(server, server.status());
			serial.drain();
			rig.network.refuse(new IllegalStateException("The network failed"));
			server.release();
			rig.ticker().fire();
			serial.drain();

			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.doubts("S"), is(false));
			assertThat(rig.driver.syncing(), is(false));
			assertThat(rig.scheduler.pending(), is(empty()));
			assertThat(live(rig), is(empty()));
		}
	}

	@Example
	public void shouldEndTheSyncAndNotHaltWhenAPeerGivesNoFutureForARequest() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final LedgerPeer mute = new LedgerPeer() {
				@Override
				public String id() {
					return "M";
				}

				@Override
				public void send(Object wire) {
					/* Nothing to say */
				}

				@Override
				public CompletableFuture<BlockBatch> request(BlockRequest request) {
					return null;
				}
			};
			final Server other = new Server("B", chain);

			rig.hears(mute, new Server("M", chain).status());

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.driver.syncing(), is(false));

			rig.hears(other, other.status());

			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	private static int kindOf(int code) {
		return code % 75 == 74 ? 15 : code % 15;
	}

	private static Server serverOf(int code, List<Server> servers) {
		return servers.get(code / 48 % servers.size());
	}

	private static Status claim(Chain chain, long height) {
		final byte[] chainId = chain.ledger.chainId();

		return new Status(chainId, height, chain.ledger.hashAt(height).orElse(height == 0 ? chainId
			: new byte[Digests.HASH_SIZE]));
	}

	private static void act(int code, DriverRig rig, List<Chain> chains, List<Server> servers, RecordingPeer peer) {
		final Server server = serverOf(code, servers);
		final long amount = code / 144;
		final Chain chain = chains.get(server.tag == 'A' ? 0 : 1);

		switch (kindOf(code)) {
			case 0 -> rig.hears(server, server.status());
			case 1 -> rig.hears(server, claim(chain, amount % 32));
			case 2 -> servers.stream().filter(Server::hasHeld).findFirst().ifPresent(Server::releaseIfHeld);
			case 3 -> servers.stream().filter(Server::hasHeld).findFirst().ifPresent(Server::failIfHeld);
			case 4 -> rig.tick(1 + amount % 5_000);
			case 5 -> fireATimer(rig, amount);
			case 6 -> rig.passGrace();
			case 7 -> deliverTheNextBlock(rig, chains.get((int) (amount % 2)), peer);
			case 8 -> {
				rig.breakNode();
				rig.tick(DriverRig.TICK);
			}
			case 9 -> rig.clock.addAndGet(1 + amount * 1_009 % 700_000);
			case 10 -> {
				server.holding = true;
				rig.hears(server, server.status());
			}
			case 11 -> server.tampering = amount % 2 == 0;
			case 12 -> server.holding = false;
			case 13 -> rig.timers().stream().filter(timer -> timer.delayMillis() == Syncer.PACE_MILLIS
				|| timer.delayMillis() == Syncer.RETRY_MILLIS).forEach(ManualScheduler.Timer::fire);
			case 14 -> rig.clock.addAndGet(-Math.min(amount % 3_000, rig.clock.get() - DriverRig.START));
			default -> rig.failCreation(new IOException("The files are gone"));
		}
	}

	private static void fireATimer(DriverRig rig, long which) {
		final List<ManualScheduler.Timer> timers = rig.timers();

		if (!timers.isEmpty()) {
			timers.get((int) (which % timers.size())).fire();
		}
	}

	private static void deliverTheNextBlock(DriverRig rig, Chain chain, RecordingPeer peer) {
		final long next = rig.ledger().height() + 1;

		if (next <= chain.ledger.height()) {
			rig.driver.onMessage(peer, new Decision(chain.block(next)));
		}
	}

	private static Policy lenient() {
		final Map<Kind, Rate> rates = new EnumMap<>(Kind.class);

		for (final Kind kind : Kind.values()) {
			rates.put(kind, new Rate(1_000_000, 1_000_000));
		}

		return new Policy(rates, rates, 1_000_000, 10, 60_000, 100);
	}

	@BeforeContainer
	static void buildTheChainsOfTheProperty() throws IOException {
		PROPERTY_CHAINS.add(new Chain(24, 10));
		PROPERTY_CHAINS.add(new Chain(24, 11));
	}

	@AfterContainer
	static void dropTheChainsOfTheProperty() throws IOException {
		for (final Chain chain : PROPERTY_CHAINS) {
			chain.close();
		}

		PROPERTY_CHAINS.clear();
	}

	/* A checkpoint on one of the two chains, or none: what the driver must not hold another block than at its height */
	private static Optional<Checkpoint> checkpointOf(List<Integer> script, List<Chain> chains) {
		final int which = script.get(1) % 5;
		final long height = 3 + script.get(2) % 20;

		return which == 2 || which == 3
			? Optional.of(new Checkpoint(height, chains.get(which - 2).block(height).hash())) : Optional.empty();
	}

	/* Which of the two chains the ledger holds the start of */
	private static boolean startsLike(Ledger ledger, Chain chain) {
		return IntStream.rangeClosed(1, (int) ledger.height())
			.allMatch(height -> chain.ledger.hashAt(height).isPresent()
				&& Arrays.equals(ledger.hashAt(height).orElseThrow(), chain.ledger.hashAt(height).orElseThrow()));
	}

	/* What must hold after any step of the property, whatever the step was: the ledger never loses a block and is the
	   start of one of the chains, never a checkpoint is contradicted, one request is out at most and the driver says
	   it syncs exactly when one is out or a sync waits to ask again, what is served that must halt the driver has
	   halted it, and a peer that served a false block is doubted for as long as the memory of it lasts */
	private static void assertSound(DriverRig rig, List<Server> servers, List<Chain> chains, Optional<Checkpoint> cp,
		long[] before, long[] doubtedUntil, long lowest) {
		final Ledger ledger = rig.ledger();

		assertThat(ledger.height() >= lowest, is(true));
		assertThat(startsLike(ledger, chains.get(0)) || startsLike(ledger, chains.get(1)), is(true));
		cp.ifPresent(checkpoint -> assertThat(ledger.hashAt(checkpoint.height()).map(held -> Arrays.equals(held,
			checkpoint.hash())).orElse(true), is(true)));

		final boolean awaiting = rig.driver.awaiting();
		final long timeouts = rig.timers().stream().filter(timer -> timer.delayMillis() == PROPERTY_TIMEOUT).count();

		assertThat(timeouts, equalTo(awaiting ? 1L : 0L));
		assertThat(!awaiting || servers.stream().anyMatch(Server::hasHeld), is(true));
		assertThat(awaiting && waiting(rig), is(false));

		if (!rig.driver.halted()) {
			assertThat(rig.driver.syncing(), equalTo(awaiting || waiting(rig)));
		}

		for (int n = 0; n < servers.size(); n++) {
			final Server server = servers.get(n);

			if (server.crossServed > before[2 * n]) {
				assertThat(rig.driver.halted(), is(true));
			}

			if (server.badNewServed > before[2 * n + 1] && !rig.driver.halted() && !rig.limiter.isPenalised(server.id)) {
				assertThat(rig.driver.doubts(server.id), is(true));
				doubtedUntil[n] = rig.clock.get() + SyncState.MEMORY_MILLIS;
			}

			if (rig.clock.get() < doubtedUntil[n] && !rig.driver.halted()) {
				assertThat(rig.driver.doubts(server.id), is(true));
			}
		}
	}

	/* The restart window is 1 ms and every step takes one, so that nothing waits for a window while the claims and
	   the marks run their own time. The waits of a sync are kept, and fired as any other timer is. */
	@Property(tries = 30)
	public void shouldFailClosedWhateverStatusesBatchesFailuresAndTimersComeInWhateverOrder(
		@ForAll @Size(min = 8, max = 80) List<@IntRange(min = 0, max = 1_000_000) Integer> script)
		throws IOException {
		final List<Chain> chains = List.copyOf(PROPERTY_CHAINS);
		final Optional<Checkpoint> checkpoint = checkpointOf(script, chains);
		final DriverOptions options = new DriverOptions(DriverRig.TICK, DriverRig.HEARTBEAT, DriverRig.STATUS,
			DriverRig.GRACE, 1, DriverRig.QUEUE, DriverRig.PER_PEER, checkpoint, PROPERTY_TIMEOUT);

		try (DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), lenient(), new DirectSerial(), options).started()) {
			final AtomicLong stamps = new AtomicLong();
			final Watch watch = new Watch() {
				@Override
				public long stamp() {
					return stamps.incrementAndGet();
				}

				@Override
				public boolean current(long stamp) {
					return stamp == stamps.get() && rig.driver.awaiting();
				}

				@Override
				public boolean ready() {
					return live(rig).size() == 1 && !rig.driver.halted();
				}

				@Override
				public long height() {
					return rig.ledger().height();
				}

				@Override
				public char chain() {
					return rig.ledger().height() == 0 ? '-' : startsLike(rig.ledger(), chains.get(0)) ? 'A' : 'F';
				}
			};
			final List<Server> servers = List.of(new Server("A", chains.get(0)).watched(watch),
				new Server("B", chains.get(0)).watched(watch), new Server("C", chains.get(0)).emptying().watched(watch),
				new Server("D", chains.get(1)).of('F').watched(watch),
				new Server("E", chains.get(1)).of('F').watched(watch));
			final RecordingPeer peer = rig.network.connect("P");
			final long[] doubtedUntil = new long[servers.size()];
			long lowest = 0;

			rig.scheduler.runAtOnce();

			if (script.get(0) % 6 > 0) {
				rig.passGrace();
			}

			if (script.get(0) % 6 == 2 || script.get(0) % 6 == 3) {
				servers.get(0).holding = true;
				servers.get(0).tampering = script.get(0) % 6 == 3;
				rig.hears(servers.get(0), servers.get(0).status());
			}

			if (script.get(0) % 6 == 4) {
				servers.get(3).holding = true;
				rig.hears(servers.get(1), servers.get(1).statusAt(10));
				rig.hears(servers.get(3), servers.get(3).status());
			}

			if (script.get(0) % 6 == 5) {
				servers.get(3).holding = true;
				rig.hears(servers.get(3), servers.get(3).status());
				deliverTheNextBlock(rig, chains.get(0), peer);
			}

			if (checkpoint.isPresent() && script.get(3) % 2 == 0) {
				final Server other = servers.get(script.get(1) % 5 == 2 ? 3 : 0);

				rig.hears(other, other.status());
			}

			for (final int code : script) {
				final long[] before = servers.stream().flatMapToLong(server -> LongStream.of(server.crossServed,
					server.badNewServed)).toArray();

				rig.clock.addAndGet(1);
				act(code, rig, chains, servers, peer);
				assertFailsClosed(rig, servers);
				assertSound(rig, servers, chains, checkpoint, before, doubtedUntil, lowest);
				lowest = rig.ledger().height();
			}
		}
	}

	@Example
	public void shouldKeepAtMostSoManyClaimsAndLetTheOldestGo() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		for (int n = 0; n < SyncState.MAX_CLAIMS + 50; n++) {
			state.heard(new Server("P" + n, null), 100 + n, 0);
		}

		assertThat(state.highest(0).getAsLong(), equalTo(100L + SyncState.MAX_CLAIMS + 49));
		assertThat(state.claimsAbove("P0", 0, 0), is(false));
		assertThat(state.claimsAbove("P50", 0, 0), is(true));
		assertThat(state.claimsAbove("P49", 0, 0), is(false));
	}

	@Example
	public void shouldLetAClaimLapseUnlessItIsRepeatedAndNotWhenTheClockIsSetBack() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);
		final Server peer = new Server("P", null);

		state.heard(peer, 10, 5_000);

		assertThat(state.highest(5_999).getAsLong(), equalTo(10L));
		assertThat(state.highest(6_000).isPresent(), is(false));
		assertThat(state.highest(1).getAsLong(), equalTo(10L));
		assertThat(state.firstAbove(5, 5_000).isPresent(), is(true));
		assertThat(state.firstAbove(10, 5_000).isPresent(), is(false));

		state.heard(peer, 12, 5_900);

		assertThat(state.highest(6_800).getAsLong(), equalTo(12L));

		state.drop("P");

		assertThat(state.highest(5_900).isPresent(), is(false));
	}

	@Example
	public void shouldRememberAPeerThatCouldNotBackItsClaimForTenMinutesAndThenForgetIt() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		state.discredit("P", 5_000);

		assertThat(state.discredited("P", 5_000), is(true));
		assertDoubtedFor(state, 5_000, SyncState.MEMORY_MILLIS);
		assertThat(state.discredited("P", 1), is(true));
		assertThat(state.discredited("Q", 5_000), is(false));
	}

	private static void assertDoubtedFor(SyncState state, long since, long millis) {
		assertThat(state.discredited("P", since + millis - 1), is(true));
		assertThat(state.discredited("P", since + millis), is(false));
	}

	@Example
	public void shouldDoubleTheDoubtWithEachFailureInARowUpToTenMinutesAndStartAgainAfterABatch() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		state.failed("P", 0, 1_000);
		assertDoubtedFor(state, 0, 1_000);

		state.failed("P", 10_000, 1_000);
		assertDoubtedFor(state, 10_000, 2_000);

		state.failed("P", 20_000, 1_000);
		assertDoubtedFor(state, 20_000, 4_000);

		for (int again = 0; again < 40; again++) {
			state.failed("P", 100_000, 1_000);
		}

		assertDoubtedFor(state, 100_000, SyncState.MEMORY_MILLIS);

		state.delivered("P");
		state.failed("P", 2_000_000, 1_000);
		assertDoubtedFor(state, 2_000_000, 1_000);
	}

	@Example
	public void shouldKeepTheLongerDoubtWhenAShorterOneComesAfterIt() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		state.discredit("P", 0);
		state.failed("P", 1_000, 1_000);

		assertDoubtedFor(state, 0, SyncState.MEMORY_MILLIS);
	}

	@Example
	public void shouldAskForARetryOnlyOnceUntilABatchIsDeliveredOrASyncBegins() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		assertThat(state.retryOnce(), is(true));
		assertThat(state.retryOnce(), is(false));

		state.delivered("P");

		assertThat(state.retryOnce(), is(true));
		assertThat(state.retryOnce(), is(false));

		state.begin(0);

		assertThat(state.retryOnce(), is(true));
	}

	@Example
	public void shouldKeepAtMostSoManyMarksAndLetTheOldestGo() {
		final SyncState state = new SyncState(1_000, EVERYONE, DriverRig.SALT);

		for (int n = 0; n < SyncState.MAX_CLAIMS + 50; n++) {
			state.discredit("P" + n, 0);
		}

		assertThat(state.discredited("P0", 0), is(false));
		assertThat(state.discredited("P49", 0), is(false));
		assertThat(state.discredited("P50", 0), is(true));
	}
}
