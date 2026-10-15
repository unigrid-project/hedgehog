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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.ledger.LedgerDriver.Checkpoint;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.PeerLimiter.Rate;
import org.unigrid.hedgehog.ledger.RecordingNetwork.RecordingPeer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.account;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.key;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.paying;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

/* The driver is the only thing that calls a node, so these tests give it events the way peers and timers do, on a
   clock and a scheduler that the test moves, and look at what the node did and at what went out to the peers */
public class LedgerDriverTest {
	/* A peer of one driver that is the other driver: what it is sent is handed to the other, who sees the first one as
	   the sender */
	private static final class Link implements LedgerPeer {
		private final String id;
		private LedgerDriver target;
		private Link back;

		private Link(String id) {
			this.id = id;
		}

		private void connect(LedgerDriver other, Link otherEnd) {
			this.target = other;
			this.back = otherEnd;
		}

		@Override
		public String id() {
			return id;
		}

		@Override
		public void send(Object wire) {
			switch (wire) {
				case Transaction transaction -> target.onTransaction(back, transaction);
				case Heartbeat heartbeat -> target.onHeartbeat(back, heartbeat);
				case Message message -> target.onMessage(back, message);
				case Status status -> target.onStatus(back, status);
				default -> throw new IllegalArgumentException("Not a wire object: " + wire);
			}
		}

		@Override
		public CompletableFuture<BlockBatch> request(BlockRequest request) {
			return new CompletableFuture<>();
		}
	}

	private static Ballot forged(Ballot real) {
		return new Ballot(real.type(), real.height(), real.view(), real.blockHash(), real.validator(),
			new byte[Ed25519.SIGNATURE_SIZE]);
	}

	private static Policy burstOf(int transactions) {
		final Map<Kind, Rate> rates = new EnumMap<>(Policy.DEFAULT.rates());

		rates.put(Kind.TRANSACTION, new Rate(transactions, 1));
		return new Policy(rates, Policy.DEFAULT.global(), 100, 10, 60_000, 100);
	}

	private static Heartbeat heartbeatAtTheTip(DriverRig rig, long time) {
		final Status tip = rig.ledger().status();

		return Heartbeat.signed(seed(1), tip.chainId(), tip.height(), tip.tipHash(), time);
	}

	@Example
	public void shouldSubmitAnAcceptedTransactionForwardItToAllButItsSenderAndDecideOnIt() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Mint mint = mint(1, 500);

			rig.driver.onTransaction(a, mint);

			assertThat(b.sent(Transaction.class), equalTo(List.of(mint)));
			assertThat(a.sent(Transaction.class), is(empty()));
			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.ledger().balanceOf(account(1)), equalTo(500L));
			assertThat(a.sent(Status.class), equalTo(List.of(rig.ledger().status())));
			assertThat(b.sent(Status.class), equalTo(List.of(rig.ledger().status())));
		}
	}

	@Example
	public void shouldNeitherForwardNorScoreATransactionItHasAcceptedBefore() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Mint mint = mint(1, 500);

			rig.driver.onTransaction(a, mint);

			for (int again = 0; again < 5; again++) {
				rig.driver.onTransaction(b, mint);
			}

			assertThat(b.sent(Transaction.class), equalTo(List.of(mint)));
			assertThat(a.sent(Transaction.class), is(empty()));
			assertThat(rig.limiter.isPenalised("B"), is(false));
		}
	}

	@Example
	public void shouldScoreATransferWithAFalseSignatureByOneAndForwardNothing() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Transfer genuine = paying(rig.ledger(), 100, 0, 0);
			final Transfer forged = new Transfer(genuine.sender(), genuine.recipient(), genuine.amount(),
				genuine.fee(), genuine.nonce(), new byte[Ed25519.SIGNATURE_SIZE]);

			rig.driver.submit(mint(1, 500)).join();
			rig.driver.onTransaction(a, forged);

			assertThat(rig.limiter.isPenalised("A"), is(false));

			rig.driver.onTransaction(a, forged);

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(b.sent(Transaction.class), equalTo(List.of(mint(1, 500))));
			assertThat(rig.ledger().pending(), equalTo(0));
		}
	}

	@Example
	public void shouldNeitherScoreNorForwardWhatOnlyThisNodesStateOrPolicyRefuses() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Transfer unfunded = paying(rig.ledger(), 100, 0, 0);
			final Transfer wrongNonce = paying(rig.ledger(), 1, 0, 40);

			for (int again = 0; again < 3; again++) {
				rig.driver.onTransaction(a, unfunded);
				rig.driver.onTransaction(a, wrongNonce);
			}

			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(b.sent(), is(empty()));
		}
	}

	@Example
	public void shouldAnswerALocalSubmitAndSendTheTransactionToEveryPeer() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Mint mint = mint(1, 500);
			final Transfer unfunded = paying(rig.ledger(), 10_000, 0, 0);

			assertThat(rig.driver.submit(mint).join(), equalTo(Optional.empty()));
			assertThat(a.sent(Transaction.class), equalTo(List.of(mint)));
			assertThat(b.sent(Transaction.class), equalTo(List.of(mint)));
			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.driver.submit(unfunded).join(), equalTo(rig.ledger().submit(unfunded)));
			assertThat(rig.driver.submit(unfunded).join().isPresent(), is(true));
			assertThat(a.sent(Transaction.class), hasSize(1));
		}
	}

	@Example
	public void shouldForwardAnAcceptedHeartbeatOnceAndNotAStaleOne() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Heartbeat beat = heartbeatAtTheTip(rig, DriverRig.START);

			rig.driver.onHeartbeat(a, beat);
			rig.driver.onHeartbeat(b, beat);
			rig.driver.onHeartbeat(a, heartbeatAtTheTip(rig, DriverRig.START - 1));

			assertThat(b.sent(Heartbeat.class), equalTo(List.of(beat)));
			assertThat(a.sent(Heartbeat.class), is(empty()));
		}
	}

	@Example
	public void shouldForwardANewConsensusMessageToAllButItsSenderAndNotOneItHolds() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final RecordingPeer c = rig.network.connect("C");
			final Proposal proposal = rig.proposal();
			final Ballot prevote = rig.ballot(Ballot.Type.PREVOTE, 3);

			rig.driver.onMessage(a, proposal);
			rig.driver.onMessage(b, proposal);
			rig.driver.onMessage(c, prevote);
			rig.driver.onMessage(c, prevote);

			assertThat(b.sent(Proposal.class), equalTo(List.of(proposal)));
			assertThat(c.sent(Proposal.class), equalTo(List.of(proposal)));
			assertThat(a.sent(Proposal.class), is(empty()));
			assertThat(a.sent(Ballot.class), hasItem(prevote));
			assertThat(b.sent(Ballot.class), hasItem(prevote));
			assertThat(c.sent(Ballot.class), not(hasItem(prevote)));
			assertThat(a.sent(Ballot.class).stream().filter(prevote::equals).count(), equalTo(1L));
		}
	}

	@Example
	public void shouldScoreAForgedMessageByTwoAndForwardNothing() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.onMessage(a, forged(rig.ballot(Ballot.Type.PREVOTE, 3)));

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(b.sent(), is(empty()));
		}
	}

	@Example
	public void shouldNotScoreAMessageOfAnotherHeight() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final Ballot ahead = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 5, 0, null);

			for (int again = 0; again < 3; again++) {
				rig.driver.onMessage(a, ahead);
			}

			assertThat(rig.limiter.isPenalised("A"), is(false));
		}
	}

	@Example
	public void shouldNotScoreWhatAnHonestPeerThatIsBehindSendsOnceTheNodeHasMovedOn() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Decision decision = new Decision(rig.certified());

			rig.driver.onMessage(b, decision);

			for (int again = 0; again < 3; again++) {
				rig.driver.onMessage(a, decision);
				rig.driver.onMessage(a, rig.proposal());
				rig.driver.onMessage(a, rig.ballot(Ballot.Type.PREVOTE, 3));
			}

			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(b.sent(Ballot.class), is(empty()));
		}
	}

	@Example
	public void shouldNotScoreAForgedMessageOfAHeightThatIsNotTheCurrentOne() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final Ballot past = forged(rig.ballot(Ballot.Type.PREVOTE, 3));
			final Ballot future = forged(Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 5, 0, null));

			rig.driver.onMessage(a, new Decision(rig.certified()));

			for (int again = 0; again < 3; again++) {
				rig.driver.onMessage(a, past);
				rig.driver.onMessage(a, future);
			}

			assertThat(rig.limiter.isPenalised("A"), is(false));
		}
	}

	@Example
	public void shouldNeitherScoreNorForwardTheSecondWordOfAnEquivocator() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Ballot first = rig.ballot(Ballot.Type.PREVOTE, 3);
			final Ballot second = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 1, 0, null);

			rig.driver.onMessage(a, first);

			for (int again = 0; again < 3; again++) {
				rig.driver.onMessage(a, second);
			}

			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(b.sent(Ballot.class), hasItem(first));
			assertThat(b.sent(Ballot.class), not(hasItem(second)));
		}
	}

	@Example
	public void shouldNeitherScoreNorForwardASignedBallotBeyondTheViewsTheEngineKeeps() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final int view = Consensus.MAX_VIEW_AHEAD + 8;
			final Ballot ahead = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 1, view, null);

			for (int again = 0; again < 3; again++) {
				rig.driver.onMessage(a, ahead);
			}

			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(b.sent(Ballot.class), not(hasItem(ahead)));
		}
	}

	@Example
	public void shouldScoreABallotOfAKeyThatIsNoValidatorByTwo() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Ballot stranger = Ballot.signed(Ballot.Type.PREVOTE, seed(9), rig.chainId(), 1, 0, null);

			rig.driver.onMessage(a, stranger);

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(b.sent(Ballot.class), not(hasItem(stranger)));
		}
	}

	@Example
	public void shouldScoreAProposalThatIsNotFromTheProposerOfItsViewByTwo() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Proposal usurper = Proposal.signed(seed(3), rig.chainId(), 1, 0, -1, rig.proposed());

			rig.driver.onMessage(a, usurper);

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(b.sent(Proposal.class), is(empty()));
		}
	}

	@Example
	public void shouldKeepTwoHonestNodesFromBanningEachOtherWhenAValidatorEquivocates() throws IOException {
		try (DriverRig left = new DriverRig(DriverRig.FOUR, seed(2), Policy.DEFAULT, new DirectSerial()).started();
			DriverRig right = new DriverRig(DriverRig.FOUR, seed(3), Policy.DEFAULT, new DirectSerial()).started()) {
			final Link towardsRight = new Link("right");
			final Link towardsLeft = new Link("left");
			final RecordingPeer byzantineToLeft = left.network.connect("byzantine");
			final RecordingPeer byzantineToRight = right.network.connect("byzantine");

			towardsRight.connect(right.driver, towardsLeft);
			towardsLeft.connect(left.driver, towardsRight);
			left.network.add(towardsRight);
			right.network.add(towardsLeft);

			for (final Ballot.Type type : Ballot.Type.values()) {
				for (int view = 0; view <= Consensus.MAX_VIEW_AHEAD; view++) {
					final Ballot one = Ballot.signed(type, seed(4), left.chainId(), 1, view, left.proposed().hash());
					final Ballot other = Ballot.signed(type, seed(4), left.chainId(), 1, view, null);

					left.driver.onMessage(byzantineToLeft, one);
					right.driver.onMessage(byzantineToRight, other);
				}
			}

			assertThat(left.limiter.isPenalised("right"), is(false));
			assertThat(right.limiter.isPenalised("left"), is(false));
			assertThat(left.limiter.isPenalised("byzantine"), is(false));
			assertThat(right.limiter.isPenalised("byzantine"), is(false));
		}
	}

	@Example
	public void shouldForwardADecisionThatCommitsToAllButItsSenderAndSayWhereItStands() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final RecordingPeer c = rig.network.connect("C");
			final Decision decision = new Decision(rig.certified());

			rig.driver.onMessage(a, decision);

			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(b.sent(Decision.class), equalTo(List.of(decision)));
			assertThat(c.sent(Decision.class), equalTo(List.of(decision)));
			assertThat(a.sent(Decision.class), is(empty()));

			for (final RecordingPeer peer : List.of(a, b, c)) {
				assertThat(peer.sent(Status.class), equalTo(List.of(rig.ledger().status())));
			}

			assertThat(rig.limiter.isPenalised("A"), is(false));
		}
	}

	@Example
	public void shouldScoreADecisionWithoutEnoughSignaturesAndForwardNothing() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.onMessage(a, new Decision(rig.thinlyCertified()));

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(b.sent(Decision.class), is(empty()));
		}
	}

	@Example
	public void shouldNeitherScoreNorForwardTheMessageThatMadeTheDecision() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final Ballot lastPrecommit = rig.ballot(Ballot.Type.PRECOMMIT, 4);

			rig.driver.onMessage(a, rig.proposal());
			rig.driver.onMessage(a, rig.ballot(Ballot.Type.PREVOTE, 3));
			rig.driver.onMessage(a, rig.ballot(Ballot.Type.PREVOTE, 4));
			rig.driver.onMessage(a, rig.ballot(Ballot.Type.PRECOMMIT, 3));
			rig.driver.onMessage(a, lastPrecommit);

			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.limiter.isPenalised("A"), is(false));
			assertThat(b.sent(Ballot.class), hasItem(rig.ballot(Ballot.Type.PRECOMMIT, 3)));
			assertThat(b.sent(Ballot.class), not(hasItem(lastPrecommit)));
		}
	}

	@Example
	public void shouldArmOneTimerForAScheduleAndPostTheTimeoutWhenItFires() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.submit(mint(1, 500));

			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				contains(Timeouts.DEFAULT.propose(0)));
			assertThat(b.sent(Ballot.class), is(empty()));

			rig.timers().get(0).fire();

			assertThat(b.sent(Ballot.class), hasSize(1));
			assertThat(b.sent(Ballot.class).get(0).type(), equalTo(Ballot.Type.PREVOTE));
			assertThat(b.sent(Ballot.class).get(0).blockHash(), equalTo(new byte[Digests.HASH_SIZE]));
			assertThat(b.sent(Ballot.class).get(0).validator(), equalTo(key(2)));
		}
	}

	@Example
	public void shouldNotPushOutATimerThatIsPendingWhenTheRestoredNodeAsksForItAgain() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			rig.driver.submit(mint(1, 500));

			final List<ManualScheduler.Timer> armed = rig.timers();

			rig.breakNode();
			rig.tick(1_000);

			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(rig.nodes.get(1).height(), equalTo(1L));
			assertThat(rig.timers(), equalTo(armed));
			assertThat(armed.get(0).cancelled(), is(false));
			assertThat(rig.scheduler.scheduled().stream()
				.filter(timer -> timer.delayMillis() == Timeouts.DEFAULT.propose(0)).count(), equalTo(1L));
		}
	}

	@Example
	public void shouldArmAgainTheTimerWhoseEventFailedAndSendNothingOfIt() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.submit(mint(1, 500));

			final ManualScheduler.Timer fired = rig.timers().get(0);

			rig.failSaves();
			fired.fire();

			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(rig.timers(), hasSize(1));
			assertThat(rig.timers().get(0), not(sameInstance(fired)));
			assertThat(rig.timers().get(0).delayMillis(), equalTo(Timeouts.DEFAULT.propose(0)));
			assertThat(b.sent(Ballot.class), is(empty()));
		}
	}

	@Example
	public void shouldCancelTheTimersOfAHeightOnceItIsDecided() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.driver.submit(mint(1, 500));

			final ManualScheduler.Timer old = rig.timers().get(0);

			rig.driver.onMessage(a, new Decision(rig.certified()));

			assertThat(old.cancelled(), is(true));
			assertThat(rig.timers(), is(empty()));
		}
	}

	@Example
	public void shouldTickAgainAfterEveryTick() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			assertThat(rig.scheduler.pending().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				contains(DriverRig.TICK));

			rig.tick(DriverRig.TICK);
			rig.tick(DriverRig.TICK);

			assertThat(rig.scheduler.scheduled(), hasSize(3));
			assertThat(rig.scheduler.pending().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				contains(DriverRig.TICK));
		}
	}

	@Example
	public void shouldSendNothingOnTheTicksOfAQuietNode() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");

			for (int tick = 0; tick < 10; tick++) {
				rig.tick(DriverRig.TICK);
			}

			assertThat(a.sent(), is(empty()));
		}
	}

	@Example
	public void shouldSignAHeartbeatAndSendAStatusOnTheirIntervals() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.tick(DriverRig.HEARTBEAT);

			assertThat(a.sent(Heartbeat.class), hasSize(1));
			assertThat(a.sent(Status.class), is(empty()));

			final Heartbeat first = a.sent(Heartbeat.class).get(0);

			assertThat(first.validator(), equalTo(key(1)));
			assertThat(first.time(), equalTo(DriverRig.START + DriverRig.HEARTBEAT));
			assertThat(first.height(), equalTo(0L));
			assertThat(first.hasValidSignature(rig.ledger().chainId()), is(true));

			rig.tick(DriverRig.STATUS - DriverRig.HEARTBEAT);

			assertThat(a.sent(Status.class), equalTo(List.of(rig.ledger().status())));
			assertThat(a.sent(Heartbeat.class), hasSize(1));

			rig.tick(DriverRig.HEARTBEAT - (DriverRig.STATUS - DriverRig.HEARTBEAT));

			assertThat(a.sent(Heartbeat.class), hasSize(2));
			assertThat(a.sent(Status.class), hasSize(1));
		}
	}

	@Example
	public void shouldSendAStatusButNeverAHeartbeatWithoutASeed() throws IOException {
		try (DriverRig rig = DriverRig.observer()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.tick(DriverRig.HEARTBEAT);
			rig.tick(DriverRig.STATUS - DriverRig.HEARTBEAT);

			assertThat(rig.built, equalTo(List.of(false)));
			assertThat(a.sent(Heartbeat.class), is(empty()));
			assertThat(a.sent(Status.class), hasSize(1));
		}
	}

	@Example
	public void shouldReplaceANodeThatFailedAndLoseNoLaterEvent() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.breakNode();
			rig.tick(DriverRig.TICK);

			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(rig.nodes.get(1).height(), equalTo(1L));
			assertThat(rig.driver.halted(), is(false));
			assertThrows(IllegalStateException.class, rig.nodes.get(0)::tick);

			rig.tick(DriverRig.TICK);
			rig.driver.onTransaction(a, mint(1, 500));

			assertThat(rig.built, hasSize(2));
			assertThat(rig.ledger().height(), equalTo(1L));
			assertThat(rig.driver.halted(), is(false));
		}
	}

	@Example
	public void shouldAnswerALocalSubmitTheLedgerCouldNotCheckWithARefusalAndKeepTheNode() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			rig.breakNode();

			assertThat(rig.driver.submit(mint(1, 500)).join().orElseThrow(), startsWith("Could not be checked"));
			assertThat(rig.built, equalTo(List.of(true)));
			assertThat(rig.driver.halted(), is(false));
		}
	}

	@Example
	public void shouldHaltWhenTheNodeFailsAgainWithinTheWindowAndIgnoreEverythingAfterwards() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.breakNode();
			rig.tick(DriverRig.TICK);
			rig.breakNode();
			rig.tick(DriverRig.TICK);

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason().orElseThrow().contains("twice"), is(true));
			assertThat(rig.built, hasSize(2));
			assertThat(rig.scheduler.pending(), is(empty()));

			rig.driver.onTransaction(a, mint(1, 500));
			rig.driver.onHeartbeat(a, heartbeatAtTheTip(rig, DriverRig.START));
			rig.driver.onMessage(a, new Decision(rig.certified()));

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(b.sent(), is(empty()));
			assertThat(rig.driver.submit(mint(1, 500)).join().isPresent(), is(true));
		}
	}

	@Example
	public void shouldReplaceTheNodeAgainWhenTheWindowHasPassed() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			rig.breakNode();
			rig.tick(DriverRig.TICK);
			rig.breakNode();
			rig.tick(DriverRig.RESTART_WINDOW);

			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.built, equalTo(List.of(true, true, true)));
		}
	}

	@Example
	public void shouldHaltWhenTheReplacementCannotBeBuilt() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			rig.breakNode();
			rig.failCreation(new IOException("The disk is gone"));
			rig.tick(DriverRig.TICK);

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason().orElseThrow().contains("The disk is gone"), is(true));
		}
	}

	@Example
	public void shouldHaltWhenTheFirstNodeCannotBeBuilt() throws IOException {
		try (DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), DriverRig.STRICT, new DirectSerial())) {
			rig.failCreation(new IOException("The files are locked"));
			rig.driver.start();

			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason().orElseThrow().contains("The files are locked"), is(true));
			assertThat(rig.scheduler.pending(), is(empty()));
		}
	}

	@Example
	public void shouldTryAFirstNodeThatFailsToStartOnceMoreAndThenHalt() throws IOException {
		try (DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), DriverRig.STRICT, new DirectSerial())) {
			rig.breakNewNodes();
			rig.driver.start();

			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.scheduler.pending(), is(empty()));
		}
	}

	@Example
	public void shouldBanThePeerAndReplaceTheNodeOnceWhenItsMessageMakesTheNodeThrow() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final LedgerNode before = rig.nodes.get(0);

			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(a, rig.proposal());

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(rig.driver.halted(), is(false));
			assertThat(b.sent(), is(empty()));
			assertThrows(IllegalStateException.class, before::tick);
		}
	}

	@Example
	public void shouldPaceTheReplacementsAndNeverHaltWhileItemsKeepMakingTheNodeThrow() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer[] peers = IntStream.range(0, 6).mapToObj(n -> rig.network.connect("P" + n))
				.toArray(RecordingPeer[]::new);

			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(peers[0], rig.proposal());

			assertThat(rig.built, hasSize(2));
			assertThat(rig.timers(), is(empty()));

			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(peers[1], rig.proposal());

			assertThat(rig.built, hasSize(2));
			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				contains(DriverRig.TICK));

			rig.driver.onMessage(peers[2], rig.proposal());
			rig.driver.onTransaction(peers[2], mint(1, 500));

			assertThat(rig.limiter.isPenalised("P2"), is(false));
			assertThat(rig.built, hasSize(2));

			rig.timers().get(0).fire();

			assertThat(rig.built, hasSize(3));
			assertThat(rig.nodes.get(2).height(), equalTo(1L));

			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(peers[3], rig.proposal());

			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				contains(2 * DriverRig.TICK));

			rig.timers().get(0).fire();
			rig.clock.addAndGet(DriverRig.RESTART_WINDOW);
			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(peers[4], rig.proposal());

			assertThat(rig.built, hasSize(5));
			assertThat(rig.timers(), is(empty()));
			assertThat(rig.driver.halted(), is(false));

			for (int banned = 0; banned <= 4; banned++) {
				assertThat(rig.limiter.isPenalised("P" + banned), is(banned != 2));
			}
		}
	}

	@Example
	public void shouldArmTheTimerAgainThatFiredWhileNoNodeWasThere() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.submit(mint(1, 500));

			final ManualScheduler.Timer propose = rig.timers().get(0);

			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(a, rig.proposal());
			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(b, rig.proposal());

			assertThat(rig.timers(), hasSize(2));

			propose.fire();
			rig.timers().stream().filter(timer -> timer.delayMillis() == DriverRig.TICK).findFirst().orElseThrow()
				.fire();

			assertThat(rig.built, hasSize(3));
			assertThat(rig.timers().stream().map(ManualScheduler.Timer::delayMillis).toList(),
				contains(Timeouts.DEFAULT.propose(0)));
		}
	}

	@Example
	public void shouldStillReplaceANodeThatFailsOnATickAfterItemsMadeItThrow() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.failSaves(new IllegalStateException("The store is broken"));
			rig.driver.onMessage(a, rig.proposal());
			rig.breakNode();
			rig.tick(DriverRig.TICK);

			assertThat(rig.built, hasSize(3));
			assertThat(rig.driver.halted(), is(false));

			rig.breakNode();
			rig.tick(DriverRig.TICK);

			assertThat(rig.driver.halted(), is(true));
		}
	}

	@Example
	public void shouldBanThePeerButKeepTheNodeWhenOnlyTheRulesThrow() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.onTransaction(a, null);

			assertThat(rig.limiter.isPenalised("A"), is(true));
			assertThat(rig.built, hasSize(1));
			assertThat(rig.driver.halted(), is(false));

			rig.driver.onTransaction(b, mint(1, 500));

			assertThat(rig.ledger().height(), equalTo(1L));
		}
	}

	@Example
	public void shouldAnswerALocalSubmitTheRulesCouldNotHandleWithARefusalAndBanNoOne() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			assertThat(rig.driver.submit(null).join().orElseThrow(), startsWith("Rejected"));
			assertThat(rig.built, hasSize(1));
			assertThat(rig.driver.submit(mint(1, 500)).join(), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldTellALocalCallerWhoseWorkFailedThatTheNodeWasReplaced() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final Mint mint = mint(1, 500);

			rig.breakSchedule();

			assertThat(rig.driver.submit(mint).join().orElseThrow(), containsString("replaced"));
			assertThat(rig.built, equalTo(List.of(true, true)));
			assertThat(a.sent(Transaction.class), equalTo(List.of(mint)));
			assertThat(rig.driver.halted(), is(false));
		}
	}

	@Example
	public void shouldHaltOnAnErrorFromTheNodeAndPassItOn() throws IOException {
		try (DriverRig rig = DriverRig.four()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.failSaves(new StackOverflowError("Too deep"));

			assertThrows(StackOverflowError.class, () -> rig.driver.onMessage(a, rig.proposal()));
			assertThat(rig.driver.halted(), is(true));
			assertThat(rig.driver.haltReason().orElseThrow(), containsString("StackOverflowError"));
			assertThat(rig.built, hasSize(1));
		}
	}

	@Example
	public void shouldSendAStatusAtOnceWhenTheClockWasSetBack() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");

			rig.tick(-5_000);

			assertThat(a.sent(Status.class), equalTo(List.of(rig.ledger().status())));
		}
	}

	@Example
	public void shouldDropAPeerOverItsBurstWithoutCallingTheNode() throws IOException {
		try (DriverRig rig = DriverRig.solo(burstOf(2))) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.onTransaction(a, mint(1, 10));
			rig.driver.onTransaction(a, mint(2, 10));
			rig.driver.onTransaction(a, mint(3, 10));

			assertThat(rig.ledger().height(), equalTo(2L));
			assertThat(rig.ledger().pending(), equalTo(0));
			assertThat(b.sent(Transaction.class), hasSize(2));
		}
	}

	@Example
	public void shouldIgnoreEveryKindFromAPenalisedPeer() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.limiter.misbehaved("A", 2);

			assertThat(rig.limiter.isPenalised("A"), is(true));

			rig.driver.onTransaction(a, mint(1, 500));
			rig.driver.onHeartbeat(a, heartbeatAtTheTip(rig, DriverRig.START));
			rig.driver.onMessage(a, new Decision(rig.certified()));

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(b.sent(), is(empty()));
		}
	}

	@Example
	public void shouldKeepSendingToTheOtherPeersWhenOneConnectionFails() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final RecordingPeer c = rig.network.connect("C");
			final Mint mint = mint(1, 500);

			b.fail();
			rig.driver.onTransaction(a, mint);

			assertThat(c.sent(Transaction.class), equalTo(List.of(mint)));
			assertThat(c.sent(Status.class), hasSize(1));
			assertThat(rig.driver.halted(), is(false));
			assertThat(rig.ledger().height(), equalTo(1L));
		}
	}

	@Example
	public void shouldStartOnceAndTakeNothingAfterItIsClosed() throws IOException {
		try (DriverRig rig = DriverRig.solo()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");

			rig.driver.start();

			assertThat(rig.built, hasSize(1));

			rig.driver.close();
			rig.driver.onTransaction(a, mint(1, 500));
			rig.driver.onMessage(a, new Decision(rig.certified()));

			assertThat(rig.ledger().height(), equalTo(0L));
			assertThat(b.sent(), is(empty()));
			assertThat(rig.scheduler.pending(), is(empty()));
			assertThat(rig.driver.submit(mint(1, 500)).join().isPresent(), is(true));
			assertThrows(IllegalStateException.class, rig.nodes.get(0)::tick);
		}
	}

	@Example
	public void shouldLeaveAClosedNodeAloneWhenATimerOrATickComesAfterTheClose() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = new DriverRig(DriverRig.FOUR, seed(2), DriverRig.STRICT, serial).started()) {
			serial.drain();
			rig.driver.submit(mint(1, 500));
			serial.drain();

			final ManualScheduler.Timer timeout = rig.timers().get(0);

			rig.driver.close();
			timeout.fire();
			rig.ticker().fire();
			serial.drain();

			assertThat(rig.built, hasSize(1));
			assertThat(rig.driver.halted(), is(false));
		}
	}

	@Example
	public void shouldRunEveryEntryPointOnTheSerialThreadWhateverThreadCallsIt() throws Exception {
		final ExecutorService serial = Executors.newSingleThreadExecutor(task -> new Thread(task, "ledger-serial"));
		final ExecutorService callers = Executors.newFixedThreadPool(8);

		try (DriverRig rig = new DriverRig(DriverRig.SOLO, seed(1), Policy.DEFAULT, serial::execute).started()) {
			final RecordingPeer a = rig.network.connect("A");
			final RecordingPeer b = rig.network.connect("B");
			final CountDownLatch go = new CountDownLatch(1);
			final List<Future<CompletableFuture<Optional<String>>>> calls = IntStream.rangeClosed(1, 8)
				.mapToObj(n -> callers.submit(() -> {
					go.await();
					rig.driver.onTransaction(a, mint(n, 10));
					return rig.driver.submit(mint(100 + n, 10));
				})).toList();

			go.countDown();

			for (final Future<CompletableFuture<Optional<String>>> call : calls) {
				assertThat(call.get(10, TimeUnit.SECONDS).get(10, TimeUnit.SECONDS), equalTo(Optional.empty()));
			}

			rig.driver.close();
			serial.submit(() -> { }).get(10, TimeUnit.SECONDS);

			assertThat(rig.ledger().height(), equalTo(16L));
			assertThat(rig.builders, equalTo(Set.of("ledger-serial")));
			assertThat(rig.network.threads(), equalTo(Set.of("ledger-serial")));
			assertThat(a.threads(), equalTo(Set.of("ledger-serial")));
			assertThat(b.threads(), equalTo(Set.of("ledger-serial")));
		} finally {
			callers.shutdownNow();
			serial.shutdownNow();
		}
	}

	@Example
	public void shouldRefuseOptionsWithoutATimeAndACheckpointBelowTheFirstBlock() {
		final byte[] hash = new byte[Digests.HASH_SIZE];
		final Optional<Checkpoint> none = Optional.empty();

		assertThrows(IllegalArgumentException.class, () -> new DriverOptions(0, 1, 1, 1, 1, none));
		assertThrows(IllegalArgumentException.class, () -> new DriverOptions(1, 0, 1, 1, 1, none));
		assertThrows(IllegalArgumentException.class, () -> new DriverOptions(1, 1, 0, 1, 1, none));
		assertThrows(IllegalArgumentException.class, () -> new DriverOptions(1, 1, 1, 0, 1, none));
		assertThrows(IllegalArgumentException.class, () -> new DriverOptions(1, 1, 1, 1, 0, none));
		assertThrows(IllegalArgumentException.class, () -> new Checkpoint(0, hash));
		assertThrows(IllegalArgumentException.class, () -> new Checkpoint(1, new byte[Digests.HASH_SIZE - 1]));
		assertThat(DriverOptions.DEFAULT, equalTo(new DriverOptions(1_000, 10_000, 10_000, 30_000, 10_000, none)));
	}

	@Example
	public void shouldKeepACheckpointHashApartFromTheArrayItWasGiven() {
		final byte[] hash = new byte[Digests.HASH_SIZE];
		final Checkpoint checkpoint = new Checkpoint(5, hash);

		hash[0] = 1;
		checkpoint.hash()[1] = 1;

		assertThat(checkpoint, equalTo(new Checkpoint(5, new byte[Digests.HASH_SIZE])));
		assertThat(checkpoint.hashCode(), equalTo(new Checkpoint(5, new byte[Digests.HASH_SIZE]).hashCode()));
		assertThat(checkpoint, not(equalTo(new Checkpoint(6, new byte[Digests.HASH_SIZE]))));
	}
}
