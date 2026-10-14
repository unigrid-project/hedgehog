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
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import static org.unigrid.hedgehog.ledger.Action.*;

/* A deterministic world for the engines: a virtual clock, an event queue, links that delay, lose and cut
   messages, and nodes that crash and come back. The same seed gives the same run, so nothing in here reads the
   wall clock or depends on the order of a hash set. */
final class SimulatedNetwork {
	static final long RESEND_MILLIS = 1000;

	private record Event(long time, long order, Runnable task) implements Comparable<Event> {
		@Override
		public int compareTo(Event other) {
			final int byTime = Long.compare(time, other.time);

			return byTime != 0 ? byTime : Long.compare(order, other.order);
		}
	}

	final List<SimNode> nodes = new ArrayList<>();
	final ValidatorSet validators;
	private final PriorityQueue<Event> queue = new PriorityQueue<>();
	private final Random random;
	private long now;
	private long counter;
	private long sent;
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

	void heal() {
		isolated = Set.of();
	}

	void crash(int index) {
		nodes.get(index).crashed = true;
	}

	/* A node that was down from the start has no height yet, so it starts instead of resuming */
	void restart(int index) {
		final SimNode node = nodes.get(index);

		node.crashed = false;
		perform(node, node.engine.height() == 0 ? start(node) : node.engine.resume(now));
	}

	void begin() {
		nodes.stream().filter(node -> !node.crashed).forEach(node -> perform(node, start(node)));
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

	private List<Action> start(SimNode node) {
		return node.engine.start(node.app.nextHeight(), validators, now);
	}

	private boolean agreeAt(int index) {
		return nodes.stream().filter(node -> node.decided.size() > index)
			.map(node -> ByteBuffer.wrap(node.decided.get(index).hash())).distinct().count() <= 1;
	}

	private void after(long delay, Runnable task) {
		queue.add(new Event(now + delay, counter++, task));
	}

	private void send(int from, int to, Message message) {
		sent++;

		if (nodes.get(to).crashed || cut(from, to) || random.nextDouble() < dropRate) {
			return;
		}

		after(minLatency + (long) (random.nextDouble() * (maxLatency - minLatency)), () -> deliver(to, message));
	}

	private boolean cut(int from, int to) {
		return !isolated.isEmpty() && isolated.contains(from) != isolated.contains(to);
	}

	private void tellOthers(SimNode from, Message message) {
		nodes.stream().filter(other -> other != from).forEach(other -> send(from.index, other.index, message));
	}

	private void deliver(int to, Message message) {
		final SimNode node = nodes.get(to);

		if (!node.crashed) {
			node.received++;
			perform(node, node.engine.onMessage(message, now));
		}
	}

	private void perform(SimNode node, List<Action> actions) {
		for (final Action action : actions) {
			switch (action) {
				case Broadcast broadcast -> tellOthers(node, broadcast.message());
				case Schedule schedule -> after(schedule.delayMillis(), () -> fire(node, schedule));
				case Decide decide -> commit(node, decide.block());
			}
		}
	}

	private void fire(SimNode node, Schedule schedule) {
		if (!node.crashed) {
			perform(node, node.engine.onTimeout(schedule.timeout(), now));
		}
	}

	private void commit(SimNode node, Block block) {
		node.app.commit(block);
		node.decided.add(block);
		perform(node, start(node));
	}

	/* Messages can be lost, so what a node has said and every block it holds is said again at a steady pace */
	private void resend() {
		nodes.stream().filter(node -> !node.crashed).forEach(node -> {
			node.engine.outbox().forEach(message -> tellOthers(node, message));
			node.decided.forEach(block -> tellOthers(node, new Decision(block)));
		});

		after(RESEND_MILLIS, this::resend);
	}
}
