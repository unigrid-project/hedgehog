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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import org.unigrid.hedgehog.ledger.Door.Admission;
import org.unigrid.hedgehog.ledger.Door.Snapshot;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.PeerLimiter.Kind;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.RecordingNetwork.RecordingPeer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

/* What the door of the driver does with an item before it takes a place in the queue: an item from an id that is
   penalised is dropped, one that is plausible and one that is not wait in shares of their own, and what the queue
   holds is counted in bytes as well as in items. The tasks wait in the queue until a test runs them, which is the
   executor that has stalled. */
public class LedgerDriverDoorTest {
	private static final long MIB = 1L << 20;
	private static final int ITEMS = 30_000;
	private static final int FLOODERS = 10;
	private static final int BURST = 2_000;

	private static DriverOptions options(int items, int perPeer, long bytes, long bytesPerPeer) {
		return new DriverOptions(DriverRig.TICK, DriverRig.HEARTBEAT, DriverRig.STATUS, DriverRig.GRACE,
			DriverRig.RESTART_WINDOW, items, perPeer, Optional.empty(),
			DriverOptions.DEFAULT_REQUEST_TIMEOUT_MILLIS, bytes, bytesPerPeer, DriverOptions.DEFAULT_RELAY_FANOUT);
	}

	/* An observer of four validators, started, whose tasks wait in the queue until a test runs them */
	private static DriverRig stalled(QueuedSerial serial, DriverOptions options, Policy policy) {
		final DriverRig rig = new DriverRig(DriverRig.FOUR, null, policy, serial, options).started();

		serial.drain();
		return rig;
	}

	/* A turn of the executor in which a peer that connects is told the status, a moment later than the last */
	private static void aTurn(DriverRig rig, QueuedSerial serial) {
		if (!rig.network.has("turn")) {
			rig.network.connect("turn");
		}

		rig.clock.addAndGet(DriverRig.TICK);
		rig.network.peers().stream().filter(peer -> peer.id().equals("turn")).forEach(rig.driver::onPeerConnected);
		serial.drain();
	}

	@Example
	public void shouldKeepTenFloodersOfWrongHeightBallotsFromStarvingTheBallotsOfHonestValidators()
		throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(ITEMS, 3_000, 64 * MIB, 8 * MIB), Policy.DEFAULT)) {
			final Ballot wrong = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 5, 0, null);
			final List<Ballot> honest = List.of(rig.ballot(Ballot.Type.PREVOTE, 1),
				rig.ballot(Ballot.Type.PREVOTE, 3), rig.ballot(Ballot.Type.PREVOTE, 4));

			for (int flooder = 0; flooder < FLOODERS; flooder++) {
				final RecordingPeer peer = rig.network.connect("J" + flooder);

				IntStream.range(0, BURST).forEach(n -> rig.driver.onMessage(peer, wrong, DriverRig.FRAME));
			}

			for (int n = 0; n < honest.size(); n++) {
				rig.driver.onMessage(rig.network.connect("H" + n), honest.get(n), DriverRig.FRAME);
			}

			serial.drain();

			assertThat(honest.stream().allMatch(ballot -> rig.nodes.get(rig.nodes.size() - 1).holds(ballot)),
				is(true));
			assertThat(rig.driver.droppedInbound(), greaterThan(0L));
		}
	}

	/* A ballot that the node holds is plausible, and played back by enough peers it would empty the bucket that all
	   peers share for the messages of the engine. A copy takes the tokens of its sender only. */
	@Example
	public void shouldKeepTenFloodersOfCopiesOfAHeldBallotFromStarvingTheBallotsOfHonestValidators()
		throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(ITEMS, 3_000, 64 * MIB, 8 * MIB), Policy.DEFAULT)) {
			final Ballot held = rig.ballot(Ballot.Type.PREVOTE, 2);
			final List<Ballot> honest = List.of(rig.ballot(Ballot.Type.PREVOTE, 1),
				rig.ballot(Ballot.Type.PREVOTE, 3), rig.ballot(Ballot.Type.PREVOTE, 4));
			final int copies = Policy.DEFAULT.rates().get(Kind.CONSENSUS).capacity();

			rig.driver.onMessage(rig.network.connect("first"), held, DriverRig.FRAME);
			serial.drain();

			for (int flooder = 0; flooder < FLOODERS; flooder++) {
				final RecordingPeer peer = rig.network.connect("C" + flooder);

				IntStream.range(0, copies).forEach(n -> rig.driver.onMessage(peer, held, DriverRig.FRAME));
				serial.drain();
			}

			for (int n = 0; n < honest.size(); n++) {
				rig.driver.onMessage(rig.network.connect("H" + n), honest.get(n), DriverRig.FRAME);
			}

			serial.drain();

			assertThat(honest.stream().allMatch(ballot -> rig.nodes.get(rig.nodes.size() - 1).holds(ballot)),
				is(true));
		}
	}

	/* Ballots of the height after the next are plausible and not held, so they take what all peers share for the
	   messages of the engine. The peer that brought a new ballot of a validator has a reserve the flood cannot touch,
	   while a peer that brought nothing yet is refused with the flooders. */
	@Example
	public void shouldKeepTakingTheBallotsOfAPeerThatBroughtNewOnesWhenFloodersEmptyWhatAllPeersShare()
		throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(ITEMS, 3_000, 64 * MIB, 8 * MIB), Policy.DEFAULT)) {
			final Ballot ahead = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 2, 0, null);
			final RecordingPeer relay = rig.network.connect("relay");
			final int floods = Policy.DEFAULT.rates().get(Kind.CONSENSUS).capacity();
			final LedgerNode node = rig.nodes.get(rig.nodes.size() - 1);

			rig.driver.onMessage(relay, rig.ballot(Ballot.Type.PREVOTE, 2), DriverRig.FRAME);
			serial.drain();

			for (int flooder = 0; flooder < FLOODERS; flooder++) {
				final RecordingPeer peer = rig.network.connect("F" + flooder);

				IntStream.range(0, floods).forEach(n -> rig.driver.onMessage(peer, ahead, DriverRig.FRAME));
				serial.drain();
			}

			rig.driver.onMessage(rig.network.connect("stranger"), rig.ballot(Ballot.Type.PREVOTE, 1),
				DriverRig.FRAME);
			rig.driver.onMessage(relay, rig.ballot(Ballot.Type.PREVOTE, 3), DriverRig.FRAME);
			rig.driver.onMessage(relay, rig.ballot(Ballot.Type.PREVOTE, 4), DriverRig.FRAME);
			serial.drain();

			assertThat(node.holds(rig.ballot(Ballot.Type.PREVOTE, 1)), is(false));
			assertThat(node.holds(rig.ballot(Ballot.Type.PREVOTE, 3)), is(true));
			assertThat(node.holds(rig.ballot(Ballot.Type.PREVOTE, 4)), is(true));
		}
	}

	@Example
	public void shouldLetJunkTakeNoMoreThanATenthOfTheSlotsAndLeaveTheRestToWhatIsPlausible() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(100, 100, 64 * MIB, 8 * MIB), Policy.DEFAULT)) {
			final Ballot wrong = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 5, 0, null);

			IntStream.range(0, 100).forEach(n -> rig.driver.onMessage(rig.network.connect("J" + n), wrong,
				DriverRig.FRAME));

			assertThat(rig.driver.queuedInbound(), equalTo(10));

			IntStream.range(0, 100).forEach(n -> rig.driver.onTransaction(rig.network.connect("T" + n),
				mint(n, 10), DriverRig.FRAME));

			assertThat(rig.driver.queuedInbound(), equalTo(100));
		}
	}

	@Example
	public void shouldLetJunkTakeNoMoreThanATenthOfTheBytes() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 1_000, 20 * MIB, 20 * MIB), Policy.DEFAULT)) {
			final Ballot wrong = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 5, 0, null);

			IntStream.range(0, 10).forEach(n -> rig.driver.onMessage(rig.network.connect("J" + n), wrong,
				(int) MIB));

			assertThat(rig.driver.queuedBytes(), equalTo(2 * MIB));
			assertThat(rig.driver.queuedInbound(), equalTo(2));

			IntStream.range(0, 20).forEach(n -> rig.driver.onTransaction(rig.network.connect("T" + n),
				mint(n, 10), (int) MIB));

			assertThat(rig.driver.queuedBytes(), equalTo(20 * MIB));
		}
	}

	@Example
	public void shouldDropWhatAPenalisedIdSendsAtTheDoorAndAskForItsDisconnectOncePerPenalty() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 8 * MIB), DriverRig.STRICT)) {
			final RecordingPeer penalised = rig.network.connect("A");

			rig.limiter.ban("A");
			aTurn(rig, serial);

			assertThat(rig.network.disconnected(), equalTo(List.of("A")));

			IntStream.range(0, 5).forEach(n -> rig.driver.onTransaction(penalised, mint(n, 10), DriverRig.FRAME));

			assertThat(rig.driver.queuedInbound(), equalTo(0));
			assertThat(rig.driver.droppedInbound(), equalTo(5L));

			aTurn(rig, serial);

			assertThat(rig.network.disconnected(), equalTo(List.of("A")));

			rig.clock.addAndGet(60_000);
			aTurn(rig, serial);
			rig.driver.onTransaction(penalised, mint(1, 10), DriverRig.FRAME);

			assertThat(rig.driver.queuedInbound(), equalTo(1));

			serial.drain();
			rig.limiter.ban("A");
			aTurn(rig, serial);

			assertThat(rig.network.disconnected(), equalTo(List.of("A", "A")));
		}
	}

	@Example
	public void shouldAskForNoDisconnectOfAnIdThatTheNetworkDoesNotKnow() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 8 * MIB), DriverRig.STRICT)) {
			rig.limiter.ban("stranger");
			aTurn(rig, serial);

			assertThat(rig.network.disconnected().isEmpty(), is(true));
		}
	}

	@Example
	public void shouldAskForTheDisconnectOfAPeerThatItsOwnRefusalsPenalised() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 8 * MIB), DriverRig.STRICT)) {
			final RecordingPeer flooder = rig.network.connect("F");

			IntStream.range(0, 400).forEach(n -> rig.driver.onStatus(flooder, rig.ledger().status(),
				DriverRig.FRAME));
			serial.drain();
			aTurn(rig, serial);

			assertThat(rig.limiter.isPenalised("F"), is(true));
			assertThat(rig.network.disconnected(), equalTo(List.of("F")));
		}
	}

	@Example
	public void shouldDropAnItemThatDoesNotFitTheBytesOfTheQueueAndTakeItOnceTheQueueHasRunDry()
		throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 2 * MIB, 2 * MIB), Policy.DEFAULT)) {
			final Transaction mint = mint(1, 10);

			rig.driver.onTransaction(rig.network.connect("A"), mint, (int) MIB);
			rig.driver.onTransaction(rig.network.connect("B"), mint, (int) MIB);

			assertThat(rig.driver.queuedBytes(), equalTo(2 * MIB));

			rig.driver.onTransaction(rig.network.connect("C"), mint, (int) MIB);

			assertThat(rig.driver.queuedInbound(), equalTo(2));
			assertThat(rig.driver.droppedInbound(), equalTo(1L));

			serial.drain();

			assertThat(rig.driver.queuedBytes(), equalTo(0L));

			rig.driver.onTransaction(rig.network.connect("C"), mint, (int) MIB);

			assertThat(rig.driver.queuedInbound(), equalTo(1));
			assertThat(rig.driver.droppedInbound(), equalTo(1L));
		}
	}

	@Example
	public void shouldLetOnePeerHoldNoMoreBytesThanItsShare() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 3 * MIB / 2), Policy.DEFAULT)) {
			final RecordingPeer peer = rig.network.connect("A");

			rig.driver.onTransaction(peer, mint(1, 10), (int) MIB);
			rig.driver.onTransaction(peer, mint(2, 10), (int) MIB);

			assertThat(rig.driver.queuedBytes(), equalTo(MIB));
			assertThat(rig.driver.droppedInbound(), equalTo(1L));

			rig.driver.onTransaction(rig.network.connect("B"), mint(3, 10), (int) MIB);

			assertThat(rig.driver.queuedBytes(), equalTo(2 * MIB));
		}
	}

	@Example
	public void shouldNeverHoldMoreThanTheBudgetWhenTwentyPeersPostTheBiggestItemsToAStalledExecutor()
		throws IOException {
		final QueuedSerial serial = new QueuedSerial();
		final long budget = 8 * MIB;
		final long share = 4 * MIB;

		try (DriverRig rig = stalled(serial, options(10_000, 1_000, budget, share), Policy.DEFAULT)) {
			for (int round = 0; round < 10; round++) {
				for (int id = 0; id < 20; id++) {
					rig.driver.onTransaction(rig.network.connect("P" + id), mint(round, 10), (int) (MIB / 2));
					assertThat(rig.driver.queuedBytes(), lessThanOrEqualTo(budget));
				}
			}

			assertThat(rig.driver.queuedBytes(), equalTo(budget));
			assertThat(rig.driver.queuedInbound(), equalTo(16));
			assertThat(rig.driver.droppedInbound(), equalTo(200L - 16));
		}
	}

	@Example
	public void shouldGiveBackWhatAnItemTookWhenItsTaskThrows() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 8 * MIB), DriverRig.STRICT)) {
			final RecordingPeer peer = rig.network.connect("A");

			rig.driver.onMessage(peer, null, (int) MIB);

			assertThat(rig.driver.queuedBytes(), equalTo(MIB));

			serial.drain();

			assertThat(rig.driver.queuedBytes(), equalTo(0L));
			assertThat(rig.driver.queuedInbound(), equalTo(0));
			assertThat(rig.driver.queuedPeers(), equalTo(0));
		}
	}

	@Example
	public void shouldGiveBackWhatAnItemTookWhenTheExecutorTakesNoMoreTasks() throws IOException {
		final QueuedSerial serial = new QueuedSerial();
		final AtomicBoolean shutDown = new AtomicBoolean();
		final Serial gate = task -> {
			if (shutDown.get()) {
				throw new RejectedExecutionException("The executor takes no more");
			}

			serial.execute(task);
		};

		try (DriverRig rig = new DriverRig(DriverRig.FOUR, null, Policy.DEFAULT, gate,
			options(1_000, 100, 64 * MIB, 8 * MIB)).started()) {
			serial.drain();
			shutDown.set(true);

			assertThrows(RejectedExecutionException.class,
				() -> rig.driver.onTransaction(rig.network.connect("A"), mint(1, 10), (int) MIB));

			assertThat(rig.driver.queuedBytes(), equalTo(0L));
			assertThat(rig.driver.queuedInbound(), equalTo(0));
			assertThat(rig.driver.queuedPeers(), equalTo(0));
		}
	}

	@Example
	public void shouldTakeAnItemOfAnySizeAsAtLeastOneByte() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 8 * MIB), Policy.DEFAULT)) {
			rig.driver.onTransaction(rig.network.connect("A"), mint(1, 10), 0);
			rig.driver.onTransaction(rig.network.connect("B"), mint(2, 10), -5);

			assertThat(rig.driver.queuedBytes(), equalTo(2L));
		}
	}

	@Example
	public void shouldSortWhatPeersSendByWhatTheDriverKnowsOfItsChain() throws IOException {
		final Set<AccountKey> validators = Set.copyOf(Stream.of(1, 2, 3, 4).map(n -> Ed25519.publicKey(seed(n)))
			.toList());
		final Door door = new Door();

		try (DriverRig rig = DriverRig.observer()) {
			final Ballot current = rig.ballot(Ballot.Type.PREVOTE, 3);
			final Ballot next = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 2, 0, null);
			final Ballot later = Ballot.signed(Ballot.Type.PREVOTE, seed(3), rig.chainId(), 3, 0, null);
			final Ballot stranger = Ballot.signed(Ballot.Type.PREVOTE, seed(9), rig.chainId(), 1, 0, null);
			final Proposal proposal = rig.proposal();
			final Decision decision = new Decision(rig.certified());
			final Heartbeat member = Heartbeat.signed(seed(2), rig.chainId(), 0, new byte[Digests.HASH_SIZE], 0);
			final Heartbeat outsider = Heartbeat.signed(seed(9), rig.chainId(), 0, new byte[Digests.HASH_SIZE], 0);

			door.publish(new Snapshot(0, validators, Set.of("bad"), Set.of("friend")));

			assertThat(door.sort("p", Kind.CONSENSUS, current), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.CONSENSUS, next), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.CONSENSUS, later), equalTo(Admission.JUNK));
			assertThat(door.sort("p", Kind.CONSENSUS, stranger), equalTo(Admission.JUNK));
			assertThat(door.sort("p", Kind.CONSENSUS, proposal), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.CONSENSUS, decision), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.HEARTBEAT, member), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.HEARTBEAT, outsider), equalTo(Admission.JUNK));
			assertThat(door.sort("p", Kind.STATUS, rig.ledger().status()), equalTo(Admission.JUNK));
			assertThat(door.sort("friend", Kind.STATUS, rig.ledger().status()), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.TRANSACTION, mint(1, 10)), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.BLOCK_REQUEST, new BlockRequest(1, 16)), equalTo(Admission.PLAUSIBLE));
			assertThat(door.sort("p", Kind.CONSENSUS, null), equalTo(Admission.JUNK));
			assertThat(door.sort("bad", Kind.TRANSACTION, mint(1, 10)), equalTo(Admission.CUT));
			assertThat(door.sort("bad", Kind.CONSENSUS, current), equalTo(Admission.CUT));

			door.publish(new Snapshot(5, validators, Set.of(), Set.of()));

			assertThat(door.sort("p", Kind.CONSENSUS, current), equalTo(Admission.JUNK));
			assertThat(door.sort("p", Kind.CONSENSUS, proposal), equalTo(Admission.JUNK));
			assertThat(door.sort("p", Kind.CONSENSUS, decision), equalTo(Admission.JUNK));
			assertThat(door.sort("bad", Kind.TRANSACTION, mint(1, 10)), equalTo(Admission.PLAUSIBLE));
		}
	}

	@Example
	public void shouldPublishWhatTheDoorKnowsAfterATurnThatChangedIt() throws IOException {
		final QueuedSerial serial = new QueuedSerial();

		try (DriverRig rig = stalled(serial, options(1_000, 100, 64 * MIB, 8 * MIB), DriverRig.STRICT)) {
			final RecordingPeer peer = rig.network.connect("A");

			assertThat(rig.driver.doorSnapshot().height(), equalTo(0L));
			assertThat(rig.driver.doorSnapshot().validators().size(), equalTo(4));

			rig.driver.onMessage(peer, new Decision(rig.certified()), DriverRig.FRAME);
			serial.drain();

			assertThat(rig.driver.doorSnapshot().height(), equalTo(1L));

			rig.limiter.ban("A");
			aTurn(rig, serial);

			assertThat(rig.driver.doorSnapshot().penalised(), containsInAnyOrder("A"));
		}
	}
}
