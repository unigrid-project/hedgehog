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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.jqwik.api.Example;
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

	/* The other end of a sync, which answers from a chain unless it is told to serve something else, to hold its
	   answers until they are released, or to fail */
	private static final class Server implements LedgerPeer {
		private record Held(BlockRequest request, CompletableFuture<BlockBatch> reply) {
		}

		private final String id;
		private final Chain chain;
		private final List<BlockRequest> requests = new ArrayList<>();
		private final Deque<Held> held = new ArrayDeque<>();
		private Function<BlockRequest, List<Block>> serving;
		private boolean holding;
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

		private Server failing(Throwable cause) {
			failure = cause;
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
			next.reply().complete(new BlockBatch(serving.apply(next.request())));
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

			requests.add(request);

			if (failure != null) {
				reply.completeExceptionally(failure);
			} else if (holding) {
				held.add(new Held(request, reply));
			} else {
				reply.complete(new BlockBatch(serving.apply(request)));
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
		final DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), policy, new DirectSerial(), trusting(trusted));
		final BlockLog log = BlockLog.open(rig.fileSystem.getPath("/node/blocks.log"));

		for (long height = 1; height <= blocks; height++) {
			log.append(chain.block(height));
		}

		log.close();
		return rig.started();
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
			final Server server = new Server("S", chain);
			final RecordingPeer watcher = rig.network.connect("W");

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(liar, liar.status());

			assertThat(rig.ledger().height(), equalTo(19L));
			assertThat(rig.limiter.isPenalised("L"), is(true));
			assertThat(liar.requests, hasSize(2));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.driver.onStatus(honest, honest.status());

			assertThat(honest.requests, contains(new BlockRequest(20, 16), new BlockRequest(36, 16)));
			assertThat(liar.requests, hasSize(2));
			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldRefuseABatchThatSkipsTheBlockItWasAskedFor() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server skipping = new Server("S", chain).serving(
				request -> chain.ledger.blocksFrom(request.from() + 1, request.max()));

			rig.driver.onStatus(skipping, skipping.status());

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

			rig.driver.onStatus(repeating, repeating.status());

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

			rig.driver.onStatus(server, server.status());
			rig.driver.onMessage(live, new Decision(chain.block(1)));

			assertThat(rig.ledger().height(), equalTo(1L));

			server.release();

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.limiter.isPenalised("S"), is(false));
			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldNotSyncFromTwoPeersAtOnceAndTurnToTheOtherWhenTheFirstFails() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server first = new Server("A", chain).holding();
			final Server second = new Server("B", chain);

			rig.driver.onStatus(first, first.status());
			rig.driver.onStatus(second, second.status());

			assertThat(first.requests, hasSize(1));
			assertThat(second.requests, is(empty()));

			first.failHeld(new IllegalStateException("The request timed out"));

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

			rig.driver.onStatus(failing, failing.status());

			assertThat(failing.requests, hasSize(1));
			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(rig.built, equalTo(List.of(false)));

			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false, true)));

			rig.driver.onStatus(other, other.status());

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(failing.requests, hasSize(1));
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

			rig.driver.onStatus(broken, new Server("A", chain).status());
			rig.driver.onStatus(other, other.status());

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldChargeAPeerThatClaimedAheadAndSendsNothingAndAskItNoMoreUntilItSpeaksAgain()
		throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server empty = new Server("E", chain).serving(request -> List.of());

			rig.driver.onStatus(empty, empty.status());
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

			rig.driver.onStatus(empty, empty.status());

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
			rig.driver.onStatus(culprit, culprit.status());

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.built, equalTo(List.of(false, false)));
			assertThat(culprit.requests, hasSize(1));

			rig.driver.onStatus(other, other.status());

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(rig.built, equalTo(List.of(false, false, true)));
		}
	}

	@Example
	public void shouldNotChargeAPeerForABatchOfBlocksTheLedgerHasPassed() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server stale = new Server("S", chain).holding().serving(request -> chain.ledger.blocksFrom(1, 2));
			final RecordingPeer live = rig.network.connect("L");

			rig.driver.onStatus(stale, stale.status());
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

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(server, server.status());

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Block 10 differs from the trusted checkpoint")));
			assertThat(rig.ledger().height(), equalTo(9L));
			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(server.requests, hasSize(1));

			rig.driver.onStatus(later, later.status());

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

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(server, server.status());
			rig.driver.onMessage(other, early);

			assertThat(watcher.sent(Ballot.class), hasItem(early));

			rig.driver.onMessage(other, new Decision(fork.block(2)));

			final int said = watcher.sent().size();

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Two certified blocks at height 2")));
			assertThat(rig.ledger().height(), equalTo(3L));

			rig.driver.onMessage(other, late);
			rig.driver.onTransaction(other, mint(9, 10));
			rig.driver.onStatus(other, server.status());

			assertThat(watcher.sent(), hasSize(said));
			assertThat(rig.driver.onBlockRequest(other, new BlockRequest(1, 16)).join().blocks(),
				equalTo(chain.ledger.blocksFrom(1, 16)));
		}
	}

	@Example
	public void shouldHaltOnACertifiedBlockOfTheTipWithAnotherHashToo() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.driver.onStatus(server, server.status());
			rig.driver.onMessage(rig.network.connect("O"), new Decision(fork.block(3)));

			assertThat(rig.driver.haltReason(), equalTo(Optional.of("Two certified blocks at height 3")));
		}
	}

	@Example
	public void shouldNotHaltOnADecisionOfAHeldHeightThatNoQuorumHasCertified() throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);
			final RecordingPeer other = rig.network.connect("O");
			final Block certified = fork.block(2);
			final Endorsement one = certified.getEndorsements().get(0);

			rig.driver.onStatus(server, server.status());
			rig.driver.onMessage(other, new Decision(certifiedBy(certified, List.of(one))));
			rig.driver.onMessage(other, new Decision(certifiedBy(certified, List.of(one, one, one))));
			rig.driver.onMessage(other, new Decision(spoiled(certified)));
			rig.driver.onMessage(other, new Decision(certifiedBy(certified, List.of())));
			rig.driver.onMessage(other, new Decision(LedgerFixtures.certify(
				certifiedBy(certified, List.of()), certified.getView(), chain.ledger.chainId(), seeds(5, 4))));

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.limiter.isPenalised("O"), is(false));

			rig.driver.onMessage(other, new Decision(certified));

			assertThat(rig.driver.halted(), is(true));
		}
	}

	@Example
	public void shouldIgnoreWithoutHaltingTheDecisionsItHoldsAlready() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);
			final RecordingPeer other = rig.network.connect("O");
			final RecordingPeer watcher = rig.network.connect("W");

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(server, server.status());
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

			rig.driver.onStatus(server, server.status());
			rig.driver.onMessage(rig.network.connect("O"), new Decision(fork.block(2)));
			rig.driver.onStatus(ahead, ahead.status());
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
	public void shouldPromoteAtOnceWhenAPeerIsAtTheSameHeight() throws IOException {
		try (Chain chain = new Chain(0, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.driver.onStatus(server, server.status());

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(server.requests, is(empty()));

			rig.passGrace();

			assertThat(rig.built, hasSize(2));
		}
	}

	@Example
	public void shouldNotPromoteWhileAPeerIsAheadEvenWhenTheGraceHasPassed() throws IOException {
		try (Chain chain = new Chain(5, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.driver.onStatus(server, server.status());
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

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(server, new Status(filled, 5, filled));

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
			rig.driver.onStatus(server, server.status());

			assertThat(rig.built, equalTo(List.of(false)));
		}
	}

	@Example
	public void shouldDemoteAPromotedNodeOnlyAfterABatchHasProvedItIsBehindAndSignAgainOnceItIsBack()
		throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.passGrace();
			rig.driver.onStatus(server, server.status());

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(server.requests, hasSize(1));

			server.release();

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(server.requests, hasSize(3));
			assertThat(rig.built, equalTo(List.of(false, true, false, true)));
		}
	}

	@Example
	public void shouldKeepSigningThroughASyncThatBringsItToTheTipInOneBatch() throws IOException {
		try (Chain chain = new Chain(9, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).holding();

			rig.passGrace();
			rig.driver.onStatus(server, server.status());
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
			rig.driver.onStatus(server, server.statusAt(3));

			assertThat(rig.built, equalTo(List.of(false, true)));
			assertThat(rig.ledger().height(), equalTo(3L));
		}
	}

	@Example
	public void shouldDemoteNoMoreThanOnceInTheRestartWindow() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).serving(
				request -> chain.ledger.blocksFrom(request.from(), 8));

			rig.passGrace();
			rig.driver.onStatus(server, server.statusAt(20));

			assertThat(rig.built, equalTo(List.of(false, true, false, true)));
			assertThat(rig.ledger().height(), equalTo(24L));

			rig.driver.onStatus(server, server.statusAt(40));

			assertThat(rig.built, hasSize(4));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldDemoteAgainOnceTheRestartWindowHasPassed() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain).serving(
				request -> chain.ledger.blocksFrom(request.from(), 8));

			rig.passGrace();
			rig.driver.onStatus(server, server.statusAt(20));
			rig.clock.addAndGet(DriverRig.RESTART_WINDOW);
			rig.driver.onStatus(server, server.statusAt(40));

			assertThat(rig.built, equalTo(List.of(false, true, false, true, false, true)));
			assertThat(rig.ledger().height(), equalTo(TIP));
		}
	}

	@Example
	public void shouldNotLetALiarWhoClaimsAheadForEverDemoteAnyOfFourSigningNodes() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig one = DriverRig.four(1, DriverRig.STRICT);
			DriverRig two = DriverRig.four(2, DriverRig.STRICT); DriverRig three = DriverRig.four(3, DriverRig.STRICT);
			DriverRig four = DriverRig.four(4, DriverRig.STRICT)) {
			final List<DriverRig> rigs = List.of(one, two, three, four);
			final List<Server> liars = rigs.stream().map(rig -> new Server("L", chain).holding()).toList();

			for (int interval = 0; interval < 10; interval++) {
				for (int n = 0; n < 4; n++) {
					rigs.get(n).clock.addAndGet(DriverRig.STATUS);
					rigs.get(n).driver.onStatus(liars.get(n), liars.get(n).statusAt(2));
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

			rig.driver.onStatus(liar, liar.statusAt(2));
			rig.passGrace();

			assertThat(rig.built, equalTo(List.of(false)));

			liar.failHeld(new IllegalStateException("The request timed out"));

			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldNotLetALiarWhoAnswersEmptyKeepAFreshNodeFromSigning() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig rig = fresh()) {
			final Server liar = new Server("L", chain).serving(request -> List.of());

			rig.driver.onStatus(liar, liar.statusAt(2));

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

			rig.driver.onStatus(silent, silent.status());
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

			rig.driver.onStatus(repeating, repeating.status());
			rig.passGrace();
			rig.clock.addAndGet(2 * DriverRig.STATUS - 1);
			rig.driver.onStatus(repeating, repeating.status());
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

			rig.driver.onStatus(liar, liar.statusAt(2));
			rig.clock.addAndGet(SyncState.MEMORY_MILLIS - 1);
			rig.driver.onStatus(liar, liar.statusAt(2));

			assertThat(liar.requests, hasSize(1));

			rig.clock.addAndGet(1);
			rig.driver.onStatus(liar, liar.statusAt(2));

			assertThat(liar.requests, hasSize(2));
		}
	}

	@Example
	public void shouldIgnoreAPeerWhoseRequestTimedOutForTenMinutes() throws IOException {
		try (Chain chain = new Chain(2, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server silent = new Server("S", chain).holding();

			rig.driver.onStatus(silent, silent.statusAt(2));
			silent.failHeld(new IllegalStateException("The request timed out"));
			rig.clock.addAndGet(DriverRig.STATUS);
			rig.driver.onStatus(silent, silent.statusAt(2));

			assertThat(silent.requests, hasSize(1));
			assertThat(rig.limiter.isPenalised("S"), is(false));
		}
	}

	@Example
	public void shouldIgnoreAPeerWhoseBlockWasRefusedForTenMinutes() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10); DriverRig rig = fresh(burstOf(Kind.STATUS, 100))) {
			final Server liar = new Server("L", chain).serving(
				request -> spoiling(chain.ledger.blocksFrom(request.from(), request.max()), 3));

			rig.driver.onStatus(liar, liar.status());
			rig.clock.addAndGet(DriverRig.TICK);
			rig.driver.onStatus(liar, liar.status());

			assertThat(liar.requests, hasSize(1));
			assertThat(rig.ledger().height(), equalTo(2L));
		}
	}

	@Example
	public void shouldNotCountAStatusWhoseTipIsNotTheBlockItHoldsAtThatHeightAndCostThePeerNothing()
		throws IOException {
		try (Chain chain = new Chain(3, 10); Chain fork = new Chain(3, 11);
			DriverRig rig = startedOver(chain, 3, null, TOUCHY)) {
			final Server other = new Server("F", fork);
			final Server honest = new Server("H", chain);

			rig.driver.onStatus(other, other.status());

			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(rig.limiter.isPenalised("F"), is(false));
			assertThat(other.requests, is(empty()));

			rig.driver.onStatus(honest, honest.status());

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

			rig.driver.onStatus(server, server.status());

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

			rig.driver.onStatus(server, server.status());

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
	public void shouldTakeNoTokenForTheBatchItAskedFor() throws IOException {
		try (Chain chain = new Chain(BLOCKS, 10);
			DriverRig rig = fresh(burstOf(Kind.BLOCK_BATCH, 1))) {
			final Server server = new Server("S", chain);

			rig.driver.onStatus(server, server.status());

			assertThat(rig.ledger().height(), equalTo(TIP));
			assertThat(server.requests, hasSize(3));
			assertThat(rig.driver.halted(), is(false));
		}
	}

	@Example
	public void shouldSyncALongChainFromOnePeerInOneRunWithoutAnyScoreOnIt() throws IOException {
		try (Chain chain = new Chain(200, 10); DriverRig rig = fresh(TOUCHY)) {
			final Server server = new Server("S", chain);

			rig.driver.onStatus(server, server.status());

			assertThat(rig.ledger().height(), equalTo(200L));
			assertThat(rig.ledger().tipHash(), equalTo(chain.ledger.tipHash()));
			assertThat(server.requests, hasSize(13));
			assertThat(rig.limiter.isPenalised("S"), is(false));
			assertThat(rig.built, equalTo(List.of(false, true)));
		}
	}

	@Example
	public void shouldSayWhereItStandsToAPeerThatConnects() throws IOException {
		try (Chain chain = new Chain(3, 10); DriverRig rig = fresh()) {
			final Server server = new Server("S", chain);

			rig.driver.onStatus(server, server.status());

			final RecordingPeer newcomer = rig.network.connect("N");

			rig.driver.onPeerConnected(newcomer);

			assertThat(newcomer.sent(Status.class), equalTo(List.of(rig.ledger().status())));
		}
	}

	@Example
	public void shouldKeepAtMostSoManyClaimsAndLetTheOldestGo() {
		final SyncState state = new SyncState(1_000);

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
		final SyncState state = new SyncState(1_000);
		final Server peer = new Server("P", null);

		state.heard(peer, 10, 5_000);

		assertThat(state.highest(5_999).getAsLong(), equalTo(10L));
		assertThat(state.highest(6_000).isPresent(), is(false));
		assertThat(state.highest(1).getAsLong(), equalTo(10L));
		assertThat(state.furthestAbove(5, 5_000).isPresent(), is(true));
		assertThat(state.furthestAbove(10, 5_000).isPresent(), is(false));

		state.heard(peer, 12, 5_900);

		assertThat(state.highest(6_800).getAsLong(), equalTo(12L));

		state.drop("P");

		assertThat(state.highest(5_900).isPresent(), is(false));
	}

	@Example
	public void shouldRememberAPeerThatCouldNotBackItsClaimForTenMinutesAndThenForgetIt() {
		final SyncState state = new SyncState(1_000);

		state.discredit("P", 5_000);

		assertThat(state.discredited("P", 5_000), is(true));
		assertThat(state.discredited("P", 5_000 + SyncState.MEMORY_MILLIS - 1), is(true));
		assertThat(state.discredited("P", 5_000 + SyncState.MEMORY_MILLIS), is(false));
		assertThat(state.discredited("P", 1), is(true));
		assertThat(state.discredited("Q", 5_000), is(false));
	}

	@Example
	public void shouldKeepAtMostSoManyMarksAndLetTheOldestGo() {
		final SyncState state = new SyncState(1_000);

		for (int n = 0; n < SyncState.MAX_CLAIMS + 50; n++) {
			state.discredit("P" + n, 0);
		}

		assertThat(state.discredited("P0", 0), is(false));
		assertThat(state.discredited("P49", 0), is(false));
		assertThat(state.discredited("P50", 0), is(true));
	}
}
