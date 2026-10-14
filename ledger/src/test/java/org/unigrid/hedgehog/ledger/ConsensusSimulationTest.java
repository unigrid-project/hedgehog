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
import java.util.List;
import java.util.Set;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.FakeApp;

public class ConsensusSimulationTest {
	private static SimulatedNetwork network(int nodes, int goal, long seed) {
		return new SimulatedNetwork(nodes, goal, seed, new Timeouts(300, 100, 100, 50, 100, 50));
	}

	private static List<List<ByteBuffer>> decidedHashes(SimulatedNetwork net) {
		return net.nodes.stream()
			.map(node -> node.decided.stream().map(block -> ByteBuffer.wrap(block.hash())).toList()).toList();
	}

	private static SimulatedNetwork slowRun(int goal, long seed) {
		final SimulatedNetwork net = network(4, goal, seed);

		net.latency(5, 200);
		net.begin();
		net.run(60_000);
		return net;
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

		assertThat(net.nodes.stream().allMatch(node -> node.decided.isEmpty()), is(true));
		assertThat(net.nodes.stream().allMatch(node -> node.engine.view() == 0), is(true));
		assertThat(net.sent(), equalTo(0L));
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
