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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.FakeApp;

public class ConsensusSimulationTest {
	private static final long PROPOSE_MILLIS = 300;

	/* The slowest of the runs that wait for a recovery needs about two virtual seconds on a network that works, so a
	   minute is more than twenty times that and still ends a run that is stuck */
	private static final long SETTLE_MILLIS = 60_000;

	/* With a liar that tells different stories the nodes decide in view 2, after the first two views have failed, in
	   about one and a third virtual seconds on links of constant delay. Half a minute is more than twenty times that
	   and still ends a run that is stuck. */
	private static final long LIAR_SETTLE_MILLIS = 30_000;

	/* A stretch in which fewer than a quorum of the nodes can talk to one another. The prevote and precommit timers
	   only arm once a quorum of ballots is in, so here they never do and the nodes stay where they are, in the view
	   and the step the outage found them in. What the minute checks is that nobody decides without a quorum. */
	private static final long OUTAGE_MILLIS = 60_000;

	/* A decision is a matter of milliseconds once the votes are in, so this catches a disagreement soon after it
	   happens rather than only when the run is over */
	private static final long CHECK_MILLIS = 250;

	/* With every message taking this long the order of the events does not depend on chance, which the scenarios with
	   a liar need to put its words where they should be heard */
	private static final long LINK_MILLIS = 40;

	private static final int FAULTY_GOAL = 3;

	/* With the precommit timeout of a second, the two views with nothing decided in them end at about 2.7 seconds on
	   links of 40 ms and the nodes then say again what they signed for the five seconds of the shortest window, so by
	   ten seconds they are silent for good, and the 20 that follow are the quiet that is looked for */
	private static final long RESTED_BY_MILLIS = 3_000;
	private static final long QUIET_AFTER_MILLIS = 10_000;
	private static final long QUIET_WINDOW_MILLIS = 20_000;
	private static final long QUIET_PRECOMMIT_MILLIS = 600;
	private static final int QUIET_SEEDS = 5;

	/* A view with nothing in it lasts under three seconds here, so a validator that could wake the nodes whenever it
	   liked would take them through dozens of views in two minutes */
	private static final long NUDGED_MILLIS = 120_000;
	private static final long LONG_NUDGED_MILLIS = 1_800_000;

	/* Views that end with nothing decided, enough to put a node that saw none of them several views behind */
	private static final int FAILED_VIEWS = 4;

	/* No messages can be lost for ever, so every node that is up decides well within this time */
	private static final long STRANDED_SETTLE_MILLIS = 300_000;
	private static final int STRANDED_SEEDS = 40;

	/* The network and the schedule of faults must not draw from the same stream, or the delays and losses would be
	   tied to the choice of node count, crashes and split */
	private static final long NETWORK_SEED_MASK = 0x5DEECE66DL;

	/* Over 1500 random runs more than half were not finished when the weather cleared, and the decided blocks came
	   from views up to the ninth, so the bad stretch is long enough to leave work and view changes for the rest */
	private static final long BAD_NETWORK_MIN_MILLIS = 20_000;
	private static final long BAD_NETWORK_SPREAD_MILLIS = 20_000;

	private static final long RESTART_WINDOW_MILLIS = 500;
	private static final int RESTART_ODDS = 10;
	private static final long[] RESTART_TIMES = {700, 1500, 2300, 3100, 4200};
	private static final Consensus.Step[] RESTART_STEPS = {Consensus.Step.PREVOTE, Consensus.Step.PRECOMMIT, null,
		Consensus.Step.PROPOSE, Consensus.Step.PRECOMMIT};
	private static final int RESTART_GOAL = 3;

	/* A node can be away for longer than the minute at most in which its peers say things again, and the chain has to
	   move while it is, so the goal is a few heights on links that take their time. A generous time after the last
	   restart is more than a hundred times what a node needs to catch up. */
	private static final long LONGEST_DOWNTIME_MILLIS = 70_000;
	private static final long LATEST_CRASH_MILLIS = 30_000;
	private static final long FIRST_CRASH_SPREAD_MILLIS = 3_000;
	private static final long BETWEEN_DOWNTIMES_SPREAD_MILLIS = 5_000;
	private static final int DOWNTIME_GOAL = 5;
	private static final long CATCH_UP_MILLIS = 300_000;

	/* Over 1500 random runs the slowest needed 5.5 seconds to finish once the network worked again, so ten minutes are
	   more than a hundred times that and still end a run that is stuck */
	private static final long RECOVERY_MILLIS = 600_000;

	private record Standing(long height, int view, Consensus.Step step, int ballots) {
	}

	private record Run(SimulatedNetwork net, Set<Integer> crashed) {
	}

	/* A validator that lies. Its engine is down, so that nothing honest gets in the way: what it says is signed with
	   its key by the test and sent to the nodes of the test's choice, at height 1 */
	private record Liar(SimulatedNetwork net, SimNode node) {
		Block block(int view, String note) {
			return node.app.block(1, view, node.app.self, 10, note);
		}

		Proposal proposal(int view, int validRound, Block block) {
			return Proposal.signed(node.seed, ConsensusFixtures.CHAIN, 1, view, validRound, block);
		}

		Ballot vote(Ballot.Type type, int view, Block block) {
			final byte[] hash = block == null ? null : block.hash();

			return Ballot.signed(type, node.seed, ConsensusFixtures.CHAIN, 1, view, hash);
		}

		void tell(int to, Message... messages) {
			Arrays.stream(messages).forEach(message -> net.send(node.index, to, message));
		}
	}

	private static Run faultyRun(long seed) {
		return faultyRun(seed, false);
	}

	/* Delays up to nearly half a second, up to two messages in five lost, as many validators down from the start as the
	   engines are meant to stand and, half of the time, the network cut in two: first a stretch of that weather, then
	   a network that works. Agreement is checked all along, and with restarts nobody must say two things either. */
	private static Run faultyRun(long seed, boolean restarting) {
		final Random random = new Random(seed);
		final int count = 4 + random.nextInt(4);
		final SimulatedNetwork net = network(count, FAULTY_GOAL, seed ^ NETWORK_SEED_MASK);
		final Set<Integer> crashed = randomNodes(random, count, random.nextInt((count - 1) / 3 + 1));

		net.latency(5, 20 + random.nextInt(400));
		net.dropRate(random.nextDouble() * 0.4);
		crashed.forEach(net::crash);

		if (random.nextBoolean()) {
			net.partition(randomNodes(random, count, 1 + random.nextInt(count - 1)));
		}

		final long badMillis = BAD_NETWORK_MIN_MILLIS + random.nextInt((int) BAD_NETWORK_SPREAD_MILLIS);
		final Runnable check = restarting ? () -> assertNoEquivocation(net) : () -> assertAgreement(net);

		if (restarting) {
			scheduleRestarts(net, random, crashed, badMillis);
		}

		net.begin();
		runChecking(net, badMillis, () -> false, check);

		net.latency(5, 50);
		net.dropRate(0);
		net.heal();
		runChecking(net, RECOVERY_MILLIS, () -> net.everyoneReached(FAULTY_GOAL, crashed), check);
		return new Run(net, crashed);
	}

	/* In each window of half a second a node that is up restarts with a chance of one in RESTART_ODDS, at a moment of
	   the window and in one of the two ways. The moments come from the seed of the run, like the rest of the weather. */
	private static void scheduleRestarts(SimulatedNetwork net, Random random, Set<Integer> down, long untilMillis) {
		for (long window = 0; window < untilMillis; window += RESTART_WINDOW_MILLIS) {
			for (int index = 0; index < net.nodes.size(); index++) {
				if (!down.contains(index) && random.nextInt(RESTART_ODDS) == 0) {
					final long at = window + random.nextInt((int) RESTART_WINDOW_MILLIS);

					scheduleRestart(net, index, at, random.nextBoolean());
				}
			}
		}
	}

	private static void scheduleRestart(SimulatedNetwork net, int index, long at, boolean loseNextEvent) {
		net.after(at, () -> {
			if (loseNextEvent) {
				net.loseNextActionsOf(index);
			} else {
				net.restartFromDisk(index);
			}
		});
	}

	/* Nodes go down and come back from their disks after a time of 0 up to 70 seconds, which is more than their peers
	   keep saying what they signed. The downtimes sit in as many tracks as the engines stand down at one time, one
	   after the other in each, and no node is down twice at once. What happens is written to the events, for a run
	   that fails. */
	private static SimulatedNetwork downtimeRun(long seed, List<String> events) {
		final Random random = new Random(seed);
		final int count = 4 + random.nextInt(4);
		final SimulatedNetwork net = network(count, DOWNTIME_GOAL, seed ^ NETWORK_SEED_MASK);
		final long[] backAt = new long[count];
		long lastRestart = 0;

		net.latency(5, 50 + random.nextInt(250));
		net.dropRate(random.nextDouble() * 0.2);

		for (int track = 0; track < (count - 1) / 3; track++) {
			long cursor = random.nextInt((int) FIRST_CRASH_SPREAD_MILLIS);

			while (cursor < LATEST_CRASH_MILLIS) {
				final OptionalInt index = nodeThatIsUpAt(random, backAt, cursor);
				long downtime = 0;

				if (index.isPresent()) {
					downtime = random.nextInt((int) LONGEST_DOWNTIME_MILLIS + 1);
					backAt[index.getAsInt()] = cursor + downtime;
					lastRestart = Math.max(lastRestart, cursor + downtime);
					scheduleDowntime(net, index.getAsInt(), cursor, downtime, events);
				}

				cursor += downtime + 1 + random.nextInt((int) BETWEEN_DOWNTIMES_SPREAD_MILLIS);
			}
		}

		net.begin();
		runChecking(net, lastRestart + 1, () -> false, () -> assertNoEquivocation(net));
		runChecking(net, CATCH_UP_MILLIS, () -> net.everyoneReached(DOWNTIME_GOAL, Set.of()),
			() -> assertNoEquivocation(net));
		return net;
	}

	/* A node with a downtime planned at or after the time is not up for a new one, whatever comes first */
	private static OptionalInt nodeThatIsUpAt(Random random, long[] backAt, long time) {
		final int first = random.nextInt(backAt.length);

		return IntStream.range(0, backAt.length).map(offset -> (first + offset) % backAt.length)
			.filter(index -> backAt[index] <= time).findFirst();
	}

	private static void scheduleDowntime(SimulatedNetwork net, int index, long at, long downtime, List<String> events) {
		events.add("node " + index + " down at " + at + " for " + downtime);
		net.after(at, () -> net.crash(index));
		net.after(at + downtime, () -> net.restartFromDisk(index));
	}

	private static Set<Integer> randomNodes(Random random, int count, int wanted) {
		final Set<Integer> nodes = new TreeSet<>();

		while (nodes.size() < wanted) {
			nodes.add(random.nextInt(count));
		}

		return nodes;
	}

	private static Liar liar(SimulatedNetwork net, int index) {
		net.crash(index);
		return new Liar(net, net.nodes.get(index));
	}

	private static SimulatedNetwork network(int nodes, int goal, long seed) {
		return new SimulatedNetwork(nodes, goal, seed, new Timeouts(PROPOSE_MILLIS, 100, 100, 50, 100, 50));
	}

	private static Standing standing(SimNode node) {
		final Consensus engine = node.engine;

		return new Standing(engine.height(), engine.view(), engine.step(), engine.storedBallots());
	}

	private static void runUntil(SimulatedNetwork net, BooleanSupplier reached) {
		while (!reached.getAsBoolean() && net.now() < 60_000) {
			net.run(net.now() + 1);
		}

		assertThat(reached.getAsBoolean(), is(true));
	}

	private static void runFor(SimulatedNetwork net, long millis) {
		net.run(net.now() + millis);
	}

	private static void runAgreeing(SimulatedNetwork net, long millis) {
		runAgreeingUntil(net, millis, () -> false);
	}

	private static void runAgreeingUntil(SimulatedNetwork net, long millis, BooleanSupplier done) {
		runChecking(net, millis, done, () -> assertAgreement(net));
	}

	private static void runWithoutEquivocation(SimulatedNetwork net, long millis, BooleanSupplier done) {
		runChecking(net, millis, done, () -> assertNoEquivocation(net));
	}

	private static void runChecking(SimulatedNetwork net, long millis, BooleanSupplier done, Runnable check) {
		for (long left = millis; left > 0 && !done.getAsBoolean(); left -= CHECK_MILLIS) {
			runFor(net, Math.min(left, CHECK_MILLIS));
			check.run();
		}
	}

	private static void assertAgreement(SimulatedNetwork net) {
		assertThat(net.agreement(), is(true));
	}

	/* No node signed two things for one view or went back to an earlier one, none sent what it had not written down
	   first, and no node has seen a validator say two things. Evidence is wiped when a height ends, so it is looked at
	   all along, not at the end. */
	private static void assertNoEquivocation(SimulatedNetwork net) {
		assertAgreement(net);
		assertThat(net.contradictions(), equalTo(0));
		assertThat(net.viewRegressions(), equalTo(0));
		assertThat(net.unsavedSends(), equalTo(0));
		assertThat(net.nodes.stream().allMatch(node -> node.engine.evidence().isEmpty()), is(true));
	}

	private static boolean nobodyDecided(SimulatedNetwork net) {
		return net.nodes.stream().allMatch(node -> node.decided.isEmpty());
	}

	private static boolean allInStep(SimulatedNetwork net, Collection<Integer> indexes, Consensus.Step step) {
		return indexes.stream().allMatch(index -> net.nodes.get(index).engine.step() == step);
	}

	private static SimNode nonProposerOfViewZero(SimulatedNetwork net, long height) {
		final AccountKey proposer = net.validators.proposerAt(height, 0);

		return net.nodes.stream().filter(node -> !Ed25519.publicKey(node.seed).equals(proposer)).findFirst()
			.orElseThrow();
	}

	/* What the liar tells a node it wants to mislead: a proposal and a prevote for the same block, and then more */
	private static Message[] story(Liar liar, Block block, Message... more) {
		final Stream<Message> told = Stream.of(liar.proposal(0, -1, block), liar.vote(PREVOTE, 0, block));

		return Stream.concat(told, Arrays.stream(more)).toArray(Message[]::new);
	}

	/* The relay sits on the path to the node, so its copy takes one millisecond while the real message, sent at the
	   same moment, takes the delay of the link */
	private static void relayWithoutProofs(SimulatedNetwork net, int to, Set<Proposal> relayed) {
		for (final SimNode node : net.nodes) {
			for (final Message message : node.engine.outbox()) {
				if (node.index != to && message instanceof Proposal proposal && !proposal.polka().isEmpty()
					&& relayed.add(proposal)) {
					net.latency(1, 1);
					net.send(node.index, to, withoutProof(proposal));
					net.latency(LINK_MILLIS, LINK_MILLIS);
				}
			}
		}
	}

	private static Proposal withoutProof(Proposal proposal) {
		return new Proposal(proposal.height(), proposal.view(), proposal.validRound(), proposal.block(), List.of(),
			proposal.sender(), proposal.signature());
	}

	private static boolean decidedFirst(SimulatedNetwork net, int index, Block block) {
		return Arrays.equals(net.nodes.get(index).decided.get(0).hash(), block.hash());
	}

	private static boolean decidedInViewTwoOrLater(SimulatedNetwork net, int index) {
		return net.nodes.get(index).decided.get(0).getCertificate().view() >= 2;
	}

	private static List<List<ByteBuffer>> decidedHashes(SimulatedNetwork net) {
		return net.nodes.stream()
			.map(node -> node.decided.stream().map(block -> ByteBuffer.wrap(block.hash())).toList()).toList();
	}

	private static SimulatedNetwork slowNetwork(int goal, long seed) {
		final SimulatedNetwork net = network(4, goal, seed);

		net.latency(5, 200);
		net.begin();
		return net;
	}

	private static SimulatedNetwork slowRun(int goal, long seed) {
		final SimulatedNetwork net = slowNetwork(goal, seed);

		net.run(60_000);
		return net;
	}

	private static int receivedByAll(SimulatedNetwork net) {
		return net.nodes.stream().mapToInt(node -> node.received).sum();
	}

	@Example
	public void shouldDecideTheSameBlocksOnAReliableNetwork() {
		final SimulatedNetwork net = network(4, 5, 1);

		net.begin();
		net.run(60_000);

		assertThat(net.everyoneReached(5, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
		assertThat(net.nodes.get(0).decided.get(0).getView(), equalTo(0));
	}

	@Example
	public void shouldMakeNoNoiseAtAllWhenThereIsNothingToDo() {
		final SimulatedNetwork net = network(4, 0, 1);

		net.begin();
		net.run(60_000);

		assertThat(nobodyDecided(net), is(true));
		assertThat(net.nodes.stream().allMatch(node -> node.engine.view() == 0), is(true));
		assertThat(net.sent(), equalTo(0L));
	}

	private static SimulatedNetwork networkWithSlowPrecommits(int nodes, int goal, long seed) {
		return new SimulatedNetwork(nodes, goal, seed,
			new Timeouts(PROPOSE_MILLIS, 100, 100, 50, SimulatedNetwork.RESEND_MILLIS, 50));
	}

	/* The proposer of the first view wakes the nodes with a block that every one of them refuses. The view of the
	   proposal and the next one end with nothing decided and nothing to wait for. The nodes say again what they signed
	   while they rest, and after that nobody talks, nobody sets a timer and no engine runs, though the nodes still hold
	   what they signed. */
	@Example
	public void shouldGoQuietAgainWhenNobodyHasWork() {
		final SimulatedNetwork net = networkWithSlowPrecommits(4, 0, 1);
		final Liar liar = liar(net, 0);
		final List<SimNode> live = net.nodes.stream().filter(node -> !node.crashed).toList();

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		nudgeWithRefusedProposal(liar, live);
		net.run(RESTED_BY_MILLIS);

		final long sentOnceRested = net.sent();

		assertThat(live.stream().noneMatch(node -> node.engine.running()), is(true));

		net.run(QUIET_AFTER_MILLIS);

		final long sent = net.sent();
		final int armed = live.stream().mapToInt(node -> node.armed).sum();
		final int received = receivedByAll(net);

		assertThat(sent, greaterThan(sentOnceRested));
		assertThat(armed, greaterThan(0));
		assertThat(live.stream().noneMatch(node -> node.engine.running()), is(true));
		assertThat(live.stream().noneMatch(node -> node.engine.outbox().isEmpty()), is(true));

		net.run(QUIET_AFTER_MILLIS + QUIET_WINDOW_MILLIS);

		assertThat(net.sent(), equalTo(sent));
		assertThat(live.stream().mapToInt(node -> node.armed).sum(), equalTo(armed));
		assertThat(receivedByAll(net), equalTo(received));
		assertThat(live.stream().noneMatch(node -> node.engine.running()), is(true));
		assertThat(nobodyDecided(net), is(true));
	}

	/* Links that take any time up to nearly the precommit timeout let some ballots of the one view arrive late, after
	   a node has rested. They must not start a chain of further views: from a bounded time on the network is silent. */
	@Example
	public void shouldGoQuietAgainWhateverTheDelayOfLinksBelowThePrecommitTimeout() {
		for (long seed = 1; seed <= QUIET_SEEDS; seed++) {
			final SimulatedNetwork net = new SimulatedNetwork(4, 0, seed,
				new Timeouts(PROPOSE_MILLIS, 100, 100, 50, QUIET_PRECOMMIT_MILLIS, 50));
			final Liar liar = liar(net, 0);
			final List<SimNode> live = net.nodes.stream().filter(node -> !node.crashed).toList();

			net.latency(5, QUIET_PRECOMMIT_MILLIS - 100);
			net.begin();
			nudgeWithRefusedProposal(liar, live);
			net.run(QUIET_AFTER_MILLIS);

			final long sent = net.sent();
			final int armed = live.stream().mapToInt(node -> node.armed).sum();
			final int received = receivedByAll(net);

			net.run(QUIET_AFTER_MILLIS + QUIET_WINDOW_MILLIS);

			assertThat("seed " + seed, armed, greaterThan(0));
			assertThat("seed " + seed, net.sent(), equalTo(sent));
			assertThat("seed " + seed, live.stream().mapToInt(node -> node.armed).sum(), equalTo(armed));
			assertThat("seed " + seed, receivedByAll(net), equalTo(received));
			assertThat("seed " + seed, live.stream().noneMatch(node -> node.engine.running()), is(true));
		}
	}

	/* A validator that signs ballots in the view after the one the nodes are in, every second, is a single voice: it
	   wakes nodes that have nothing to do no more than once in half a minute, so in two minutes they run a view in
	   each half minute at most, where they ran dozens when any ballot woke them */
	@Example
	public void shouldLetOneValidatorWakeTheNodesNoMoreThanOnceInHalfAMinuteWhenNobodyHasWork() {
		final SimulatedNetwork net = networkWithSlowPrecommits(4, 0, 1);
		final Liar liar = liar(net, 1);
		final List<SimNode> live = net.nodes.stream().filter(node -> !node.crashed).toList();
		final int mostViews = (int) (NUDGED_MILLIS / Consensus.LONE_WAKE_MILLIS);

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		nudgeAheadEverySecond(net, liar, live);
		net.run(NUDGED_MILLIS);

		assertThat(live.stream().allMatch(node -> node.engine.view() > 0), is(true));
		assertThat(live.stream().allMatch(node -> node.engine.view() <= mostViews), is(true));
		assertThat(nobodyDecided(net), is(true));
	}

	/* A node that was cut off while the others ran views with nothing decided rests far behind them, and one of those
	   others is down by the time there is work, so a block needs the node that is behind. The proposal of the view
	   the others begin next does not wake it alone, as nothing further on than its own next view does, but the other
	   node that the proposal wakes signs ballots in that view, and the two of them, more than a third, wake it: it
	   skips to their view and the three decide the proposal. */
	@Example
	public void shouldBringANodeThatRestsFarBehindIntoTheViewOfAProposalThatNeedsIt() {
		final SimulatedNetwork net = networkWithSlowPrecommits(4, 0, 1);
		final SimNode behind = net.nodes.get(3);
		final List<SimNode> others = net.nodes.stream().filter(node -> node != behind).toList();

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.partition(Set.of(behind.index));
		setWork(others, 1, true);
		net.begin();
		runUntil(net, () -> others.stream().allMatch(node -> node.engine.view() >= FAILED_VIEWS));
		setWork(others, 0, false);
		runFor(net, QUIET_AFTER_MILLIS);

		final int rested = others.get(0).engine.view();
		final SimNode proposer = net.nodes.get(net.validators.keys().indexOf(net.validators.proposerAt(1, rested + 1)));
		final SimNode down = others.stream().filter(node -> node != proposer).findFirst().orElseThrow();

		assertThat(others.stream().noneMatch(node -> node.engine.running() || node.engine.view() != rested), is(true));
		assertThat(behind.engine.running(), is(false));
		assertThat(behind.engine.view(), equalTo(0));
		assertThat(proposer != behind, is(true));

		net.crash(down.index);
		net.heal();
		proposer.app.goal = 1;
		net.work(proposer.index);
		runAgreeingUntil(net, SETTLE_MILLIS, () -> net.everyoneReached(1, Set.of(down.index)));

		assertThat(net.everyoneReached(1, Set.of(down.index)), is(true));
		assertThat(behind.decided.get(0).getView(), equalTo(rested + 1));
	}

	/* Half an hour of a liar that wakes the nodes whenever it may takes them through dozens of views. What a node says
	   again on a tick is its last two views, three messages each at most, and a few older ones in turn, to each of
	   the three others, so what the honest nodes send in the last minute stays within that, however many views the
	   height has. */
	@Example
	public void shouldKeepWhatTheNodesSayAgainBoundedWhileALiarWakesThemForHalfAnHour() {
		final SimulatedNetwork net = networkWithSlowPrecommits(4, 0, 1);
		final Liar liar = liar(net, 1);
		final List<SimNode> live = net.nodes.stream().filter(node -> !node.crashed).toList();
		final long perTick = (long) live.size() * (net.nodes.size() - 1) * (6 + Consensus.OLDER_PER_RESEND);
		final long nudgesPerMinute = 60L * 2 * live.size();

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		nudgeAheadEverySecond(net, liar, live);
		net.run(LONG_NUDGED_MILLIS - 60_000);

		final long before = net.sent();

		net.run(LONG_NUDGED_MILLIS);

		assertThat(live.stream().allMatch(node -> node.engine.outbox().size() > 6 + Consensus.OLDER_PER_RESEND),
			is(true));
		assertThat(net.sent() - before - nudgesPerMinute, lessThan(60 * perTick));
		assertThat(live.stream().allMatch(node -> node.engine.view() <= LONG_NUDGED_MILLIS / Consensus.LONE_WAKE_MILLIS),
			is(true));
	}

	private static void setWork(List<SimNode> nodes, int goal, boolean refused) {
		nodes.forEach(node -> {
			node.app.goal = goal;
			node.app.refuse = refused;
		});
	}

	private static void nudgeAheadEverySecond(SimulatedNetwork net, Liar liar, List<SimNode> live) {
		final int ahead = live.stream().mapToInt(node -> node.engine.view()).max().orElseThrow() + 1;

		live.forEach(node -> liar.tell(node.index, liar.vote(PREVOTE, ahead, null), liar.vote(PRECOMMIT, ahead, null)));
		net.after(SimulatedNetwork.RESEND_MILLIS, () -> nudgeAheadEverySecond(net, liar, live));
	}

	/* The liar is the proposer of the first view and every node that is up refuses its block */
	private static void nudgeWithRefusedProposal(Liar liar, List<SimNode> live) {
		final Proposal nudge = liar.proposal(0, -1, liar.block(0, "refused"));

		live.forEach(node -> {
			node.app.refuse = true;
			liar.tell(node.index, nudge);
		});
	}

	/* Only one node has work, so the others rest after every view that has nothing for them and wake to its messages,
	   at most once in half a minute each. When three in ten messages are lost, the node that works must still get what
	   the resting nodes signed in the view it waits in, or it would wait for ever. One node is down all along. */
	@Example
	public void shouldNotLeaveTheNodeWithWorkWaitingForAMessageTheRestingNodesLost() {
		for (long seed = 1; seed <= STRANDED_SEEDS; seed++) {
			final int down = (int) (seed % 4);
			final int working = (int) ((down + 1 + seed % 3) % 4);
			final SimulatedNetwork net = networkWithSlowPrecommits(4, 0, seed);

			net.nodes.get(working).app.goal = 1;
			net.dropRate(0.3);
			net.crash(down);
			net.begin();
			runAgreeingUntil(net, STRANDED_SETTLE_MILLIS, () -> net.everyoneReached(1, Set.of(down)));

			assertThat("seed " + seed, net.everyoneReached(1, Set.of(down)), is(true));
		}
	}

	@Example
	public void shouldGiveTheSameRunForTheSameSeed() {
		final SimulatedNetwork first = slowRun(4, 7);
		final SimulatedNetwork second = slowRun(4, 7);

		assertThat(first.everyoneReached(4, Set.of()), is(true));
		assertThat(decidedHashes(first), equalTo(decidedHashes(second)));
		assertThat(first.sent(), equalTo(second.sent()));
	}

	@Example
	public void shouldDecideTheSameHashesInEveryRepeatedRunWithSlowLinks() {
		final List<List<ByteBuffer>> reference = decidedHashes(slowRun(3, 21));

		for (int repeat = 0; repeat < 2; repeat++) {
			assertThat(decidedHashes(slowRun(3, 21)), equalTo(reference));
		}

		assertThat(reference.stream().allMatch(hashes -> hashes.size() == 3), is(true));
	}

	@Example
	public void shouldDeliverNothingToACrashedNode() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.crash(0);
		net.begin();
		net.run(120_000);

		assertThat(net.nodes.get(0).decided.isEmpty(), is(true));
		assertThat(net.nodes.get(0).engine.view(), equalTo(0));
		assertThat(net.nodes.get(0).engine.height(), equalTo(0L));
		assertThat(net.nodes.get(0).received, equalTo(0));
		assertThat(net.nodes.get(1).received, greaterThan(0));
		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldStartANodeThatWasDownFromTheStartWhenItComesBack() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.crash(0);
		net.begin();
		net.run(20_000);
		net.restart(0);
		net.run(120_000);

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldKeepTheStateOfANodeThatCrashesMidHeightAndComesBack() {
		final SimulatedNetwork net = network(4, 8, 1);
		final SimNode node = nonProposerOfViewZero(net, 2);

		net.begin();
		runUntil(net, () -> node.engine.height() == 2 && node.engine.storedBallots() > 0);

		final Standing before = standing(node);
		final int firedBefore = node.fired;

		net.crash(node.index);
		net.run(net.now() + 1000);
		assertThat(standing(node), equalTo(before));
		assertThat(node.fired, equalTo(firedBefore));

		net.restart(node.index);
		assertThat(standing(node), equalTo(before));

		net.run(60_000);
		assertThat(net.everyoneReached(8, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldLetACrashTakeTheTimersOfTheNodeWithIt() {
		final SimulatedNetwork net = network(4, 3, 1);
		final SimNode waiting = nonProposerOfViewZero(net, 1);

		net.dropRate(1.0);
		net.begin();
		net.run(100);
		net.crash(waiting.index);
		net.run(150);
		net.restart(waiting.index);

		net.run(150 + PROPOSE_MILLIS - 1);
		assertThat(waiting.engine.step(), equalTo(Consensus.Step.PROPOSE));

		net.run(150 + PROPOSE_MILLIS);
		assertThat(waiting.engine.step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldDeliverAMessageSentWhileTheNodeWasDownThatArrivesAfterItCameBack() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.latency(40, 40);
		net.begin();
		net.run(30);
		net.crash(1);
		net.run(50);
		net.restart(1);
		net.run(85);

		assertThat(net.nodes.get(1).received, greaterThan(0));
	}

	@Example
	public void shouldLeaveAnIsolatedNodeOutOfTheAgreementOfTheOthers() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.partition(Set.of(0));
		net.begin();
		net.run(120_000);

		assertThat(net.nodes.get(0).received, equalTo(0));
		assertThat(net.nodes.get(0).decided.isEmpty(), is(true));
		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldDeliverNothingWhenEveryMessageIsLost() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.dropRate(1.0);
		net.begin();
		net.run(60_000);

		assertThat(net.sent(), greaterThan(0L));
		assertThat(receivedByAll(net), equalTo(0));
		assertThat(nobodyDecided(net), is(true));
	}

	@Example
	public void shouldDecideNothingWithoutAQuorumAndEverythingOnceTheNetworkHeals() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.partition(Set.of(0, 1));
		net.begin();
		net.run(30_000);

		assertThat(nobodyDecided(net), is(true));
		assertThat(net.agreement(), is(true));

		net.heal();
		net.run(net.now() + 180_000);

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldMoveOnWhenTheProposerOfTheFirstViewIsCrashed() {
		final SimulatedNetwork net = network(4, 5, 2);

		net.crash(0);
		net.begin();
		net.run(SETTLE_MILLIS);

		assertThat(net.everyoneReached(5, Set.of(0)), is(true));
		assertThat(net.agreement(), is(true));
		assertThat(net.nodes.get(1).decided.get(0).getView(), greaterThan(0));
		assertThat(net.nodes.get(1).decided.get(4).getView(), greaterThan(0));
	}

	@Example
	public void shouldDecideNothingOnEitherSideOfASplitWithoutAQuorumAndEverythingOnceItHeals() {
		final SimulatedNetwork net = network(7, 3, 3);

		net.partition(Set.of(0, 1, 2));
		net.begin();
		net.run(OUTAGE_MILLIS);

		assertThat(nobodyDecided(net), is(true));
		assertThat(net.agreement(), is(true));

		net.heal();
		runFor(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldKeepGoingWithOneValidatorGoneForGood() {
		final SimulatedNetwork net = network(4, 4, 4);

		net.crash(3);
		net.begin();
		net.run(SETTLE_MILLIS);

		assertThat(net.everyoneReached(4, Set.of(3)), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldBringARestartedNodeBackToTheChain() {
		final SimulatedNetwork net = network(4, 5, 5);

		net.crash(2);
		net.begin();
		net.run(OUTAGE_MILLIS);

		assertThat(net.everyoneReached(5, Set.of(2)), is(true));
		assertThat(net.nodes.get(2).decided.isEmpty(), is(true));
		assertThat(net.agreement(), is(true));

		net.restart(2);
		runFor(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(5, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldAgreeWithANodeThatCrashedInTheMiddleOfAViewChangeAndCameBackLater() {
		final SimulatedNetwork net = network(4, 3, 8);
		final SimNode node = net.nodes.get(3);

		net.crash(0);
		net.begin();
		runUntil(net, () -> node.engine.view() == 1);
		net.crash(node.index);
		runFor(net, OUTAGE_MILLIS);

		assertThat(nobodyDecided(net), is(true));
		assertThat(net.agreement(), is(true));

		net.restart(node.index);
		runFor(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(net.agreement(), is(true));
	}

	@Example
	public void shouldLetAnIsolatedNodeCatchUpThroughResentDecisionsOnceTheNetworkHeals() {
		final SimulatedNetwork net = network(4, 3, 9);

		net.partition(Set.of(3));
		net.begin();
		net.run(OUTAGE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of(3)), is(true));
		assertThat(net.nodes.get(3).decided.isEmpty(), is(true));
		assertThat(net.agreement(), is(true));

		net.heal();
		runFor(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertThat(net.agreement(), is(true));
	}

	/* The proposals alone, without the prevotes of the liar: no block gets a prevote quorum in view 0 */
	@Example
	public void shouldMoveOnWhenTheProposerTellsTwoHalvesOfTheNetworkTwoBlocks() {
		final SimulatedNetwork net = network(4, 3, 6);
		final Liar liar = liar(net, 0);
		final Block one = liar.block(0, "one");
		final Block two = liar.block(0, "two");
		final Set<Integer> honest = Set.of(1, 2, 3);

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(50, () -> {
			liar.tell(1, liar.proposal(0, -1, one));
			liar.tell(2, liar.proposal(0, -1, two));
			liar.tell(3, liar.proposal(0, -1, two));
		});
		net.run(100);

		assertThat(allInStep(net, honest, Consensus.Step.PREVOTE), is(true));

		runAgreeing(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(net.nodes.get(1).decided.get(0).getView(), greaterThan(0));
	}

	/* The two nodes told about the second block see a prevote quorum for it, the liar and themselves, and lock on it.
	   The node told about the first block counts only the first prevote of the liar and never sees that quorum, so
	   it can follow a later view only by the proof that the block put forward again carries. */
	@Example
	public void shouldAgreeOnTheLockedBlockWhenTheProposerProposesAndPrevotesTwoBlocks() {
		final SimulatedNetwork net = network(4, 3, 6);
		final Liar liar = liar(net, 0);
		final Block one = liar.block(0, "one");
		final Block two = liar.block(0, "two");
		final Set<Integer> honest = Set.of(1, 2, 3);

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(50, () -> {
			liar.tell(1, story(liar, one));
			liar.tell(2, story(liar, two));
			liar.tell(3, story(liar, two));
		});
		net.run(100);

		assertThat(allInStep(net, honest, Consensus.Step.PREVOTE), is(true));
		assertThat(honest.stream().allMatch(index -> net.nodes.get(index).engine.storedBallots() == 2), is(true));

		runAgreeingUntil(net, LIAR_SETTLE_MILLIS, () -> net.everyoneReached(3, Set.of(0)));

		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(honest.stream().allMatch(index -> decidedFirst(net, index, two)), is(true));
	}

	/* The liar also takes back its precommit, which each node sees as a second word of the liar in that view and
	   keeps as evidence. Its first word is a precommit for no block, which makes the third precommit of view 0 at the
	   nodes that lock and so takes them to the next view instead of letting them decide at once. */
	@Example
	public void shouldAgreeOnTheLockedBlockWhenTheProposerAlsoPrecommitsTwice() {
		final SimulatedNetwork net = network(4, 3, 6);
		final Liar liar = liar(net, 0);
		final Block one = liar.block(0, "one");
		final Block two = liar.block(0, "two");
		final Set<Integer> honest = Set.of(1, 2, 3);

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(50, () -> {
			liar.tell(1, story(liar, one, liar.vote(PRECOMMIT, 0, null), liar.vote(PRECOMMIT, 0, one)));
			liar.tell(2, story(liar, two, liar.vote(PRECOMMIT, 0, null), liar.vote(PRECOMMIT, 0, two)));
			liar.tell(3, story(liar, two, liar.vote(PRECOMMIT, 0, null), liar.vote(PRECOMMIT, 0, two)));
		});
		net.run(100);

		assertThat(allInStep(net, honest, Consensus.Step.PREVOTE), is(true));
		assertThat(honest.stream().allMatch(index -> net.nodes.get(index).engine.storedBallots() == 3), is(true));
		assertThat(honest.stream().allMatch(index -> net.nodes.get(index).engine.evidence().size() == 1), is(true));

		runAgreeingUntil(net, LIAR_SETTLE_MILLIS, () -> net.everyoneReached(3, Set.of(0)));

		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(honest.stream().allMatch(index -> decidedFirst(net, index, two)), is(true));
	}

	/* A relay on the way to node 1 hears every block that the honest nodes put forward again and delivers a copy
	   without its proof a moment before the real one. If the proof were not part of what the proposer signed, the copy
	   would pass for the proposal of the view and the real one would be dropped as a second proposal of that view, so
	   that node 1 could never prevote the locked block and the others could never reach a quorum without it. */
	@Example
	public void shouldDecideAlthoughARelayDeliversEveryReProposalWithoutItsProofFirst() {
		final SimulatedNetwork net = network(4, 3, 6);
		final Liar liar = liar(net, 0);
		final Block one = liar.block(0, "one");
		final Block two = liar.block(0, "two");
		final Set<Proposal> relayed = new HashSet<>();

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(50, () -> {
			liar.tell(1, story(liar, one));
			liar.tell(2, story(liar, two));
			liar.tell(3, story(liar, two));
		});

		for (long step = 0; step < LIAR_SETTLE_MILLIS && !net.everyoneReached(3, Set.of(0)); step++) {
			net.run(net.now() + 1);
			relayWithoutProofs(net, 1, relayed);
		}

		assertThat(relayed.isEmpty(), is(false));
		assertThat(net.agreement(), is(true));
		assertThat(net.everyoneReached(3, Set.of(0)), is(true));
		assertThat(Set.of(1, 2, 3).stream().allMatch(index -> decidedFirst(net, index, two)), is(true));
	}

	/* Node 1 is the proposer of view 1 and node 0, the one of view 0, is down. The block the liar puts forward is a
	   right one, with the header of view 0, so that it is the proof alone that the nodes have to judge: ballots that
	   carry the keys of the validators and the signature of the liar. */
	@Example
	public void shouldIgnoreAMadeUpProofOfAProposerInALaterViewAndStillAgree() {
		final SimulatedNetwork net = network(7, 1, 15);
		final Liar liar = liar(net, 1);
		final List<Integer> honest = List.of(2, 3, 4, 5, 6);
		final Block made = net.nodes.get(2).app.block(1, 0, net.validators.proposerAt(1, 0), 10, "made up");
		final byte[] signature = liar.vote(PREVOTE, 0, made).signature();
		final List<Ballot> madeUp = honest.stream().map(index -> new Ballot(PREVOTE, 1, 0, made.hash(),
			net.validators.keys().get(index), signature)).toList();

		net.crash(0);
		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(1, () -> honest.forEach(to -> liar.tell(to,
			Proposal.signed(liar.node.seed, ConsensusFixtures.CHAIN, 1, 1, 0, made, madeUp))));
		runAgreeingUntil(net, LIAR_SETTLE_MILLIS, () -> net.everyoneReached(1, Set.of(0, 1)));

		assertThat(net.everyoneReached(1, Set.of(0, 1)), is(true));
		assertThat(honest.stream().noneMatch(index -> decidedFirst(net, index, made)), is(true));
		assertThat(honest.stream().allMatch(index -> decidedInViewTwoOrLater(net, index)), is(true));
	}

	@Example
	public void shouldRecordAValidatorThatPrevotesAnotherBlockAndPrecommitsTwiceAndStillDecideTheProposedBlock() {
		final SimulatedNetwork net = network(4, 1, 11);
		final Liar liar = liar(net, 3);
		final Block other = liar.block(0, "other");

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(1, () -> IntStream.range(0, 3).forEach(to -> liar.tell(to, liar.vote(PREVOTE, 0, other),
			liar.vote(PRECOMMIT, 0, other), liar.vote(PRECOMMIT, 0, null))));
		net.run(2 * LINK_MILLIS);

		assertThat(net.nodes.subList(0, 3).stream().allMatch(node -> node.engine.evidence().size() == 1), is(true));

		runAgreeing(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(1, Set.of(3)), is(true));
		assertThat(net.nodes.get(0).decided.get(0).getView(), equalTo(0));
	}

	@Example
	public void shouldRecordAValidatorThatContradictsItselfAndDecideTheBlockOfItsFirstWord() {
		final SimulatedNetwork net = network(4, 1, 10);
		final Liar liar = liar(net, 0);
		final Block block = liar.block(0, "block");

		net.crash(3);
		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(1, () -> IntStream.of(1, 2).forEach(to -> liar.tell(to, liar.proposal(0, -1, block),
			liar.vote(PREVOTE, 0, block), liar.vote(PRECOMMIT, 0, block), liar.vote(PRECOMMIT, 0, null))));
		net.run(2 * LINK_MILLIS);

		assertThat(net.nodes.get(1).engine.evidence().size(), equalTo(1));
		assertThat(net.nodes.get(2).engine.evidence().size(), equalTo(1));

		runAgreeing(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(1, Set.of(0, 3)), is(true));
		assertThat(net.nodes.get(1).decided.get(0).hash(), equalTo(block.hash()));
	}

	/* Node 0 is cut off from the locked nodes once they have heard its proposal and prevote, so it decides alone
	   while they lock on the same block and never see enough precommits to decide. The nil precommit of the liar, at
	   three links, makes the third precommit of view 0 and so arms the timer that takes the locked nodes to view 1;
	   without it they would sit in the precommit step with no timer and the attack would never be tried. Its
	   proposal, at eight links, arrives when they are already in view 1. */
	@Example
	public void shouldKeepTheNodesLockedOnABlockThatAnotherNodeDecidedWhenALiarProposesAnotherBlock() {
		final SimulatedNetwork net = network(4, 3, 12);
		final Liar liar = liar(net, 1);
		final Block other = liar.block(1, "other");
		final List<Integer> locked = List.of(2, 3);

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.after(LINK_MILLIS + 1, () -> locked.forEach(to -> net.cutLink(0, to)));
		net.after(3 * LINK_MILLIS, () -> locked.forEach(to -> liar.tell(to, liar.vote(PRECOMMIT, 0, null))));
		net.after(8 * LINK_MILLIS, () -> locked.forEach(to -> liar.tell(to, liar.proposal(1, -1, other),
			liar.vote(PREVOTE, 1, other), liar.vote(PRECOMMIT, 1, other))));
		runAgreeing(net, 10 * LINK_MILLIS);

		assertThat(locked.stream().allMatch(index -> net.nodes.get(index).engine.view() == 1), is(true));
		assertThat(allInStep(net, locked, Consensus.Step.PREVOTE), is(true));

		runAgreeing(net, OUTAGE_MILLIS);

		assertThat(net.nodes.get(0).decided.size(), equalTo(1));
		assertThat(locked.stream().allMatch(index -> net.nodes.get(index).decided.isEmpty()), is(true));
		assertThat(locked.stream().allMatch(index -> net.nodes.get(index).engine.lockedView() == 0), is(true));

		net.heal();
		runAgreeing(net, SETTLE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of(1)), is(true));
	}

	@Example
	public void shouldDecideWithTwoValidatorsGoneInANetworkOfSeven() {
		final SimulatedNetwork net = network(7, 4, 13);

		net.crash(0);
		net.crash(1);
		net.begin();
		runAgreeingUntil(net, SETTLE_MILLIS, () -> net.everyoneReached(4, Set.of(0, 1)));

		assertThat(net.everyoneReached(4, Set.of(0, 1)), is(true));
	}

	@Example
	public void shouldDecideNothingWithMoreValidatorsGoneThanTheEnginesStandAndAgreeWhenOneComesBack() {
		final SimulatedNetwork net = network(4, 3, 14);

		net.crash(2);
		net.crash(3);
		net.begin();
		runAgreeing(net, OUTAGE_MILLIS);

		assertThat(nobodyDecided(net), is(true));

		net.restart(3);
		runAgreeingUntil(net, SETTLE_MILLIS, () -> net.everyoneReached(3, Set.of(2)));

		assertThat(net.everyoneReached(3, Set.of(2)), is(true));
	}

	@Property(tries = 40)
	public void shouldAlwaysAgreeAndFinishOnceTheNetworkSettles(@ForAll long seed) {
		final Run run = faultyRun(seed);

		assertThat(run.net().agreement(), is(true));
		assertThat(run.net().everyoneReached(FAULTY_GOAL, run.crashed()), is(true));
	}

	/* A proposer that came back from its disk holds its valid block but not the prevotes that prove it, and only its
	   peers saying their old prevotes again give them back: this run stalled for good when they said again no more
	   than their last two views */
	@Example
	public void shouldDecideWhenAProposerThatCameBackNeedsOldPrevotesOfItsPeers() {
		final Run run = faultyRun(55, true);

		assertThat(run.net().everyoneReached(FAULTY_GOAL, run.crashed()), is(true));
		assertNoEquivocation(run.net());
	}

	@Property(tries = 40)
	public void shouldNeverEquivocateUnderRandomRestartsFromDisk(@ForAll long seed) {
		final Run run = faultyRun(seed, true);

		assertThat(run.net().agreement(), is(true));
		assertThat(run.net().everyoneReached(FAULTY_GOAL, run.crashed()), is(true));
		assertNoEquivocation(run.net());
	}

	/* If a seed fails, the events of its run are in the message of the failure */
	@Property(tries = 30)
	public void shouldAgreeAndCatchUpWhenNodesAreDownLongerThanTheirPeersSayThingsAgain(@ForAll long seed) {
		final List<String> events = new ArrayList<>();

		try {
			final SimulatedNetwork net = downtimeRun(seed, events);

			assertThat(net.everyoneReached(DOWNTIME_GOAL, Set.of()), is(true));
			assertNoEquivocation(net);
		} catch (AssertionError failure) {
			throw new AssertionError("Seed " + seed + ": " + String.join("; ", events), failure);
		}
	}

	@Example
	public void shouldGiveTheSameRunWithRestartsForTheSameSeed() {
		final Run first = faultyRun(31, true);
		final Run second = faultyRun(31, true);

		assertThat(first.net().everyoneReached(FAULTY_GOAL, first.crashed()), is(true));
		assertThat(decidedHashes(first.net()), equalTo(decidedHashes(second.net())));
		assertThat(first.net().sent(), equalTo(second.net().sent()));
		assertThat(first.net().now(), equalTo(second.net().now()));
	}

	/* A node that restarts is found in the state it wrote down */
	private static void restartAndExpectTheSameState(SimulatedNetwork net, SimNode node) {
		final SignState before = node.engine.snapshot();

		assertThat(node.decided.size(), lessThan(RESTART_GOAL));
		net.restartFromDisk(node.index);
		assertThat(node.engine.snapshot(), equalTo(before));
	}

	private static List<Message> signedIn(List<Action> actions) {
		return actions.stream().filter(action -> action instanceof Action.Broadcast)
			.map(action -> ((Action.Broadcast) action).message())
			.filter(message -> message instanceof Ballot || message instanceof Proposal).toList();
	}

	/* The node dies at the end of its next event, once the sign state of it is written: what the event signed is in
	   the state that comes back, though nothing of it was done */
	private static void loseTheNextEventOf(SimulatedNetwork net, SimNode node) {
		net.loseNextActionsOf(node.index);
		runUntil(net, () -> node.loses == null);

		assertThat(node.lost.isEmpty(), is(false));
		assertThat(node.decided.size(), lessThan(RESTART_GOAL));
		assertThat(node.engine.outbox().containsAll(signedIn(node.lost)), is(true));
	}

	/* The links take between a fifth and six tenths of a second, so a height lasts several seconds and every restart
	   finds the node in a height that is not over. Each restart is made at the first moment from its time on at which
	   the node is in the step it is meant to hit; the one that loses an event follows the next event of the node. */
	@Example
	public void shouldAgreeWhenNodesRestartFromDiskAtEveryStep() {
		final SimulatedNetwork net = network(4, RESTART_GOAL, 5);
		final Set<Consensus.Step> stepsHit = EnumSet.noneOf(Consensus.Step.class);

		net.latency(200, 600);
		net.begin();

		for (int i = 0; i < RESTART_TIMES.length; i++) {
			final SimNode node = net.nodes.get(i % 4);
			final Consensus.Step step = RESTART_STEPS[i];

			runWithoutEquivocation(net, RESTART_TIMES[i] - net.now(), () -> false);

			if (step == null) {
				loseTheNextEventOf(net, node);
			} else {
				runUntil(net, () -> node.engine.step() == step);
				stepsHit.add(node.engine.step());
				restartAndExpectTheSameState(net, node);
			}
		}

		runWithoutEquivocation(net, SETTLE_MILLIS, () -> net.everyoneReached(RESTART_GOAL, Set.of()));

		assertThat(stepsHit, equalTo(EnumSet.allOf(Consensus.Step.class)));
		assertThat(net.everyoneReached(RESTART_GOAL, Set.of()), is(true));
		assertThat(decidedHashes(net).stream().distinct().count(), equalTo(1L));
		assertNoEquivocation(net);
	}

	@Example
	public void shouldHaveWrittenTheSignStateBeforeAnythingItSignedLeavesTheNode() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.begin();
		net.run(SETTLE_MILLIS);

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertThat(net.sent(), greaterThan(0L));
		assertThat(net.unsavedSends(), equalTo(0));
		assertThat(net.contradictions(), equalTo(0));
	}

	/* The node dies at the end of the first event that asks for anything, which is its prevote for the proposal, once
	   the sign state with the prevote was written. Nothing of the event happens, so the peers never get the prevote
	   from it: the only copies on their way are those of the node that came back, which says again what it signed. */
	@Example
	public void shouldTellAgainWhatTheLostEventSignedWhenTheNodeComesBackFromTheDisk() {
		final SimulatedNetwork net = network(4, 3, 3);
		final SimNode node = nonProposerOfViewZero(net, 1);
		final int peers = net.nodes.size() - 1;

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.loseNextActionsOf(node.index);
		runUntil(net, () -> node.loses == null);

		final List<Message> signed = signedIn(node.lost);

		assertThat(signed.size(), equalTo(1));
		assertThat(node.persisted.ballots().containsAll(signed), is(true));
		assertThat(node.engine.outbox().containsAll(signed), is(true));
		assertThat(net.inFlight(signed.get(0)), equalTo(peers));

		runWithoutEquivocation(net, SETTLE_MILLIS, () -> net.everyoneReached(3, Set.of()));

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertNoEquivocation(net);
	}

	/* The block was decided and the node died before it committed it, so its ledger is a block short while the sign
	   state is that of the engine that decided. It restores as running and undecided, and its peers tell it the
	   decision again. */
	@Example
	public void shouldDecideAgainWhenTheEventThatDecidedIsLost() {
		final SimulatedNetwork net = network(4, 3, 7);
		final SimNode node = net.nodes.get(2);

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		net.loseNextActionsOf(node.index,
			actions -> actions.stream().anyMatch(action -> action instanceof Action.Decide));
		runUntil(net, () -> node.loses == null);

		assertThat(node.lost.stream().anyMatch(action -> action instanceof Action.Decide), is(true));
		assertThat(node.decided.isEmpty(), is(true));
		assertThat(node.engine.height(), equalTo(1L));

		runWithoutEquivocation(net, SETTLE_MILLIS, () -> net.everyoneReached(3, Set.of()));

		assertThat(net.everyoneReached(3, Set.of()), is(true));
		assertNoEquivocation(net);
	}

	@Example
	public void shouldLetARestartFromDiskTakeTheTimersOfTheOldProcessWithIt() {
		final SimulatedNetwork net = network(4, 3, 1);
		final SimNode waiting = nonProposerOfViewZero(net, 1);

		net.dropRate(1.0);
		net.begin();
		net.run(100);
		net.restartFromDisk(waiting.index);
		net.run(100 + PROPOSE_MILLIS - 1);

		assertThat(waiting.engine.step(), equalTo(Consensus.Step.PROPOSE));

		net.run(100 + PROPOSE_MILLIS);

		assertThat(waiting.engine.step(), equalTo(Consensus.Step.PREVOTE));
	}

	/* The nodes rest after a view with nothing in it and say again what they signed for a while. A restart from disk
	   opens that window anew, which is all the noise it may make before the network is silent again. */
	@Example
	public void shouldGoQuietAgainAfterRestartsFromDisk() {
		final SimulatedNetwork net = networkWithSlowPrecommits(4, 0, 1);
		final Liar liar = liar(net, 0);
		final List<SimNode> live = net.nodes.stream().filter(node -> !node.crashed).toList();

		net.latency(LINK_MILLIS, LINK_MILLIS);
		net.begin();
		nudgeWithRefusedProposal(liar, live);
		net.run(QUIET_AFTER_MILLIS);

		final long sentWhileQuiet = net.sent();

		live.forEach(node -> net.restartFromDisk(node.index));

		assertThat(net.sent(), greaterThan(sentWhileQuiet));

		net.run(2 * QUIET_AFTER_MILLIS);

		final long sent = net.sent();
		final int armed = live.stream().mapToInt(node -> node.armed).sum();

		net.run(2 * QUIET_AFTER_MILLIS + QUIET_WINDOW_MILLIS);

		assertThat(net.sent(), equalTo(sent));
		assertThat(live.stream().mapToInt(node -> node.armed).sum(), equalTo(armed));
		assertThat(live.stream().noneMatch(node -> node.engine.running()), is(true));
		assertThat(nobodyDecided(net), is(true));
		assertNoEquivocation(net);
	}

	@Example
	public void shouldGiveTheSameRunWithFaultsForTheSameSeed() {
		final Run first = faultyRun(31);
		final Run second = faultyRun(31);

		assertThat(first.net().everyoneReached(FAULTY_GOAL, first.crashed()), is(true));
		assertThat(decidedHashes(first.net()), equalTo(decidedHashes(second.net())));
		assertThat(first.net().sent(), equalTo(second.net().sent()));
		assertThat(first.net().now(), equalTo(second.net().now()));
	}

	@Example
	public void shouldGiveADifferentRunForADifferentSeed() {
		final SimulatedNetwork seven = slowNetwork(4, 7);
		final SimulatedNetwork eight = slowNetwork(4, 8);

		runUntil(seven, () -> !seven.nodes.get(0).decided.isEmpty());
		runUntil(eight, () -> !eight.nodes.get(0).decided.isEmpty());

		final boolean decidedAtOtherTimes = seven.now() != eight.now();

		seven.run(60_000);
		eight.run(60_000);

		assertThat(decidedAtOtherTimes || seven.sent() != eight.sent(), is(true));
	}

	@Example
	public void shouldKeepEveryMessageOnItsWayForTheMinimumLatency() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.latency(40, 40);
		net.begin();

		final long sentAtTheStart = net.sent();

		net.run(39);

		assertThat(sentAtTheStart, greaterThan(0L));
		assertThat(receivedByAll(net), equalTo(0));

		net.run(40);

		assertThat((long) receivedByAll(net), equalTo(sentAtTheStart));
	}

	@Example
	public void shouldLetAMessageTakeTheMaximumLatencyToo() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.latency(39, 40);
		net.begin();
		net.run(39);

		final int atTheMinimum = receivedByAll(net);

		net.run(40);

		assertThat(atTheMinimum, lessThan(receivedByAll(net)));
	}

	@Example
	public void shouldCutOneDirectionOfALinkUntilTheNetworkHeals() {
		final SimulatedNetwork net = network(4, 0, 1);
		final Message message = ConsensusFixtures.ballot(PREVOTE, 1, 0, null);

		net.cutLink(0, 1);
		net.send(0, 1, message);
		net.send(1, 0, message);
		net.run(100);

		assertThat(net.nodes.get(1).received, equalTo(0));
		assertThat(net.nodes.get(0).received, equalTo(1));

		net.heal();
		net.send(0, 1, message);
		net.run(200);

		assertThat(net.nodes.get(1).received, equalTo(1));
	}

	@Example
	public void shouldLetACrashedNodeSpeakThroughSend() {
		final SimulatedNetwork net = network(4, 0, 1);

		net.crash(0);
		net.send(0, 1, ConsensusFixtures.ballot(PREVOTE, 1, 0, null));
		net.run(100);

		assertThat(net.nodes.get(1).received, equalTo(1));
	}

	@Example
	public void shouldRunATaskAtTheTimeItWasSetFor() {
		final SimulatedNetwork net = network(4, 0, 1);
		final List<Long> ranAt = new ArrayList<>();

		net.after(100, () -> ranAt.add(net.now()));
		net.run(99);

		assertThat(ranAt.isEmpty(), is(true));

		net.run(150);

		assertThat(ranAt, equalTo(List.of(100L)));
	}

	@Example
	public void shouldRefuseToBeginTwice() {
		final SimulatedNetwork net = network(4, 3, 1);

		net.begin();

		Expect.assertThrows(IllegalStateException.class, net::begin);
	}

	@Example
	public void shouldRefuseALatencyThatIsNotAPositiveRange() {
		final SimulatedNetwork net = network(4, 3, 1);

		Expect.assertThrows(IllegalArgumentException.class, () -> net.latency(0, 5));
		Expect.assertThrows(IllegalArgumentException.class, () -> net.latency(10, 5));
	}

	@Example
	public void shouldSeeNoAgreementWhenTwoNodesDecidedDifferentBlocksAtOneHeight() {
		final SimulatedNetwork net = network(2, 0, 1);
		final AccountKey proposer = net.validators.proposerAt(1, 0);
		final Block one = net.nodes.get(0).app.block(1, 0, proposer, 100, "one");
		final Block other = net.nodes.get(0).app.block(1, 0, proposer, 100, "other");

		net.nodes.get(0).decided.add(one);
		net.nodes.get(1).decided.add(other);

		assertThat(net.agreement(), is(false));
	}

	@Example
	public void shouldSeeNoAgreementWhenTheNodesPartAtALaterHeight() {
		final SimulatedNetwork net = network(2, 0, 1);
		final AccountKey proposer = net.validators.proposerAt(1, 0);
		final FakeApp app = net.nodes.get(0).app;
		final Block first = app.block(1, 0, proposer, 100, "first");

		net.nodes.forEach(node -> node.decided.add(first));
		net.nodes.get(0).decided.add(app.block(2, 0, proposer, 100, "one"));
		net.nodes.get(1).decided.add(app.block(2, 0, proposer, 100, "other"));

		assertThat(net.agreement(), is(false));
	}

	@Example
	public void shouldSeeAgreementWhenNodesDecidedTheSameBlocksOrOneIsBehind() {
		final SimulatedNetwork net = network(2, 0, 1);
		final AccountKey proposer = net.validators.proposerAt(1, 0);
		final Block one = net.nodes.get(0).app.block(1, 0, proposer, 100, "one");

		net.nodes.get(0).decided.add(one);
		assertThat(net.agreement(), is(true));

		net.nodes.get(1).decided.add(one);
		assertThat(net.agreement(), is(true));
	}
}
