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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import static org.unigrid.hedgehog.ledger.Action.*;

/* A deterministic world for the engines: a virtual clock, an event queue, links that delay, lose and cut
   messages, and nodes that crash and come back, with their memory or with only the sign state they last wrote.
   The same seed gives the same run, so nothing in here reads the wall clock or depends on the order of a hash
   set. */
final class SimulatedNetwork {
	static final long RESEND_MILLIS = 1000;
	private static final int PROPOSAL_KIND = Ballot.Type.values().length;

	/* A delivery carries its message, so that a test can ask what is on its way */
	private record Event(long time, long order, Runnable task, Message carries) implements Comparable<Event> {
		@Override
		public int compareTo(Event other) {
			final int byTime = Long.compare(time, other.time);

			return byTime != 0 ? byTime : Long.compare(order, other.order);
		}
	}

	private record Link(int from, int to) {
	}

	/* What a node can say once in a view: a prevote, a precommit or a proposal */
	private record Slot(int node, int kind, long height, int view) {
	}

	final List<SimNode> nodes = new ArrayList<>();
	final ValidatorSet validators;
	private final PriorityQueue<Event> queue = new PriorityQueue<>();
	private final Set<Link> cutLinks = new HashSet<>();
	private final Map<Slot, Message> said = new HashMap<>();
	private final Random random;
	private long now;
	private long counter;
	private long sent;
	private int contradictions;
	private boolean begun;
	private long minLatency = 5;
	private long maxLatency = 50;
	private double dropRate;
	private Set<Integer> isolated = Set.of();

	SimulatedNetwork(int count, int goal, long seed, Timeouts timeouts) {
		this.random = new Random(seed);
		this.validators = ConsensusFixtures.validators(count);

		for (int i = 0; i < count; i++) {
			nodes.add(new SimNode(i, validators, goal, timeouts));
		}
	}

	long now() {
		return now;
	}

	long sent() {
		return sent;
	}

	void latency(long min, long max) {
		if (min < 1 || max < min) {
			throw new IllegalArgumentException("Latency needs 1 <= min <= max, found " + min + " and " + max);
		}

		minLatency = min;
		maxLatency = max;
	}

	void dropRate(double rate) {
		dropRate = rate;
	}

	/* The nodes of the group can talk to one another and to nobody else */
	void partition(Set<Integer> group) {
		isolated = Set.copyOf(group);
	}

	/* One direction only: what the first node says no longer reaches the second, while it still hears it */
	void cutLink(int from, int to) {
		cutLinks.add(new Link(from, to));
	}

	void heal() {
		isolated = Set.of();
		cutLinks.clear();
	}

	/* The timers of a process die with it, so the ones armed before the crash must not fire after a restart */
	void crash(int index) {
		final SimNode node = nodes.get(index);

		node.crashed = true;
		node.incarnation++;
	}

	/* A node that was down from the start has no height yet, so it starts instead of resuming */
	void restart(int index) {
		final SimNode node = nodes.get(index);

		node.crashed = false;

		if (node.engine.height() == 0) {
			startHeight(node);
		} else {
			handle(node, () -> node.engine.resume(now));
		}
	}

	/* The process dies and starts again with an engine of its own that knows only the sign state the node last wrote,
	   so what the old one heard is gone and its timers are dead */
	void restartFromDisk(int index) {
		final SimNode node = nodes.get(index);

		node.incarnation++;
		node.crashed = false;
		node.engine = new Consensus(node.app, ConsensusFixtures.CHAIN, node.seed, node.timeouts);
		handle(node, () -> node.engine.restore(node.persisted, validators, now));
	}

	/* The node dies at the end of its next event that asks for anything, after the sign state of that event reached the
	   disk and before any of the actions happened: no message leaves, no timer is armed, no block is committed. It
	   then comes back from the disk, so the peers are told again by the restored node, if at all. Only the next such
	   event is lost. */
	void loseNextActionsOf(int index) {
		loseNextActionsOf(index, actions -> true);
	}

	/* The same for the next event whose actions the test accepts, say the one that decides */
	void loseNextActionsOf(int index, Predicate<List<Action>> accepted) {
		nodes.get(index).loses = actions -> !actions.isEmpty() && accepted.test(actions);
	}

	/* How many copies of the message are on their way to someone */
	int inFlight(Message message) {
		return (int) queue.stream().filter(event -> message.equals(event.carries())).count();
	}

	/* Messages that left a node before the sign state holding them was written */
	int unsavedSends() {
		return nodes.stream().mapToInt(node -> node.sentUnsaved).sum();
	}

	/* Messages a node signed for a view in which it had signed another one */
	int contradictions() {
		return contradictions;
	}

	void begin() {
		if (begun) {
			throw new IllegalStateException("The network has begun already");
		}

		begun = true;
		nodes.stream().filter(node -> !node.crashed).forEach(this::startHeight);
		after(RESEND_MILLIS, this::resend);
	}

	void run(long untilMillis) {
		while (!queue.isEmpty() && queue.peek().time() <= untilMillis) {
			final Event event = queue.poll();

			now = event.time();
			event.task().run();
		}

		now = Math.max(now, untilMillis);
	}

	/* No two nodes decided different blocks at one height */
	boolean agreement() {
		final int longest = nodes.stream().mapToInt(node -> node.decided.size()).max().orElse(0);

		return IntStream.range(0, longest).allMatch(this::agreeAt);
	}

	boolean everyoneReached(int heights, Set<Integer> excluded) {
		return nodes.stream().filter(node -> !excluded.contains(node.index))
			.allMatch(node -> node.decided.size() >= heights);
	}

	private void startHeight(SimNode node) {
		handle(node, () -> node.engine.start(node.app.nextHeight(), validators, now));
	}

	/* The sign state is written before the first action of an event is performed, as nothing the node signed may leave
	   it before it is on the disk. It goes through its bytes, as the file would hold it. */
	private void handle(SimNode node, Supplier<List<Action>> event) {
		final List<Action> actions = event.get();

		node.persisted = SignState.decode(node.engine.snapshot().encode(), validators.size());

		if (node.loses != null && node.loses.test(actions)) {
			node.loses = null;
			node.lost = actions;
			restartFromDisk(node.index);
		} else {
			perform(node, actions);
		}
	}

	private boolean agreeAt(int index) {
		return nodes.stream().filter(node -> node.decided.size() > index)
			.map(node -> ByteBuffer.wrap(node.decided.get(index).hash())).distinct().count() <= 1;
	}

	void after(long delay, Runnable task) {
		queue.add(new Event(now + delay, counter++, task, null));
	}

	/* The links are checked as for any message, but the sender is not: a crashed node can be made to speak, so that
	   a test can play a validator that lies without an engine of its own getting in the way */
	void send(int from, int to, Message message) {
		sent++;

		if (cut(from, to) || random.nextDouble() < dropRate) {
			return;
		}

		queue.add(new Event(now + travelTime(), counter++, () -> deliver(to, message), message));
	}

	private long travelTime() {
		return Math.min(maxLatency, minLatency + (long) (random.nextDouble() * (maxLatency - minLatency + 1)));
	}

	private boolean cut(int from, int to) {
		return cutLinks.contains(new Link(from, to)) || separated(from, to);
	}

	private boolean separated(int from, int to) {
		return !isolated.isEmpty() && isolated.contains(from) != isolated.contains(to);
	}

	private void tellOthers(SimNode from, Message message) {
		audit(from, message);
		nodes.stream().filter(other -> other != from).forEach(other -> send(from.index, other.index, message));
	}

	/* What a node signs has to be on its disk when it leaves, and what it says again has to be the very same message */
	private void audit(SimNode from, Message message) {
		if (message instanceof Ballot ballot && ballot.validator().equals(from.app.self)) {
			note(from, new Slot(from.index, ballot.type().ordinal(), ballot.height(), ballot.view()), ballot,
				from.persisted.ballots().contains(ballot));
		} else if (message instanceof Proposal proposal && proposal.sender().equals(from.app.self)) {
			note(from, new Slot(from.index, PROPOSAL_KIND, proposal.height(), proposal.view()), proposal,
				from.persisted.proposals().contains(proposal));
		}
	}

	private void note(SimNode from, Slot slot, Message message, boolean saved) {
		if (!saved) {
			from.sentUnsaved++;
		}

		if (!said.computeIfAbsent(slot, key -> message).equals(message)) {
			contradictions++;
		}
	}

	private void deliver(int to, Message message) {
		final SimNode node = nodes.get(to);

		if (!node.crashed) {
			node.received++;
			handle(node, () -> node.engine.onMessage(message, now));
		}
	}

	private void perform(SimNode node, List<Action> actions) {
		for (final Action action : actions) {
			switch (action) {
				case Broadcast broadcast -> tellOthers(node, broadcast.message());
				case Schedule schedule -> arm(node, schedule);
				case Decide decide -> commit(node, decide.block());
			}
		}
	}

	private void arm(SimNode node, Schedule schedule) {
		final int armedIn = node.incarnation;

		node.armed++;
		after(schedule.delayMillis(), () -> fire(node, armedIn, schedule.timeout()));
	}

	private void fire(SimNode node, int armedIn, Timeout timeout) {
		if (node.incarnation == armedIn) {
			node.fired++;
			handle(node, () -> node.engine.onTimeout(timeout, now));
		}
	}

	private void commit(SimNode node, Block block) {
		node.app.commit(block);
		node.decided.add(block);
		startHeight(node);
	}

	/* Messages can be lost, so every node is resumed and every block it holds is said again at a steady pace. A
	   resting engine says what it signed again for a while only, which is what lets a chain with nothing to do go
	   silent. */
	private void resend() {
		nodes.stream().filter(node -> !node.crashed).forEach(node -> {
			handle(node, () -> node.engine.resume(now));
			node.decided.forEach(block -> tellOthers(node, new Decision(block)));
		});

		after(RESEND_MILLIS, this::resend);
	}
}
