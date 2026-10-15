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
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PRECOMMIT;
import static org.unigrid.hedgehog.ledger.Ballot.Type.PREVOTE;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.*;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* An engine that is restored from its snapshot at any point of a run signs what the engine that never stopped signs. A
   reference engine, validator 4 of four, is fed a random run of what the others can send: ballots and proposals of
   views around its own, polkas, validators that contradict themselves, a ballot far beyond the window, timeouts of its
   current view and work turning up. After each event a copy is restored from the snapshot of the reference, through
   the bytes, and fed what the peers would say again, which is everything the reference has heard until then, and the
   rest of the run. Every event happens at the same time, so that a block the engine proposes is the same in both. */
public class ConsensusRestoreEquivalenceTest {
	private static final Timeouts TIMEOUTS = new Timeouts(3000, 500, 1000, 300, 1500, 700);
	private static final long AT = 100;
	private static final int EVENTS = 40;
	private static final int WORK = 3;
	private static final int FAR_AHEAD = 1000;
	private static final int OTHERS = 3;

	private final ValidatorSet validators = validators(4);

	private record Input(Message message, Timeout timeout, boolean work) {
		static Input of(Message message) {
			return new Input(message, null, false);
		}

		void feed(Consensus node, FakeApp app) {
			if (message != null) {
				node.onMessage(message, AT);
			} else if (timeout != null) {
				node.onTimeout(timeout, AT);
			} else {
				app.goal = WORK;
				node.onWork(AT);
			}
		}
	}

	private record Run(Consensus reference, List<Input> script, List<SignState> states, List<Integer> goals) {
	}

	private Block block(int view, boolean first) {
		return new FakeApp(validators, key(4), 0).block(1, view, validators.proposerAt(1, view), 100,
			first ? "first" : "second");
	}

	private Block randomBlock(Random random, int view) {
		final int pick = random.nextInt(5);

		return pick == 0 ? null : block(view, pick < 4);
	}

	private int randomView(Random random, int current) {
		return random.nextInt(3) == 0 ? random.nextInt(current + 4) : current;
	}

	private Ballot randomBallot(Random random, int current) {
		final Ballot.Type type = random.nextBoolean() ? PREVOTE : PRECOMMIT;
		final int view = randomView(random, current);

		return ballot(type, 1 + random.nextInt(OTHERS), view, randomBlock(random, view));
	}

	private boolean isOwnView(int view) {
		return validators.proposerAt(1, view).equals(key(4));
	}

	private Input randomProposal(Random random, int current) {
		final int view = randomView(random, current);

		return isOwnView(view) ? Input.of(randomBallot(random, current))
			: Input.of(proposal(view, -1, block(view, random.nextInt(4) != 0)));
	}

	private Input randomReProposal(Random random, int current) {
		final int view = 1 + randomView(random, current);
		final int earlier = random.nextInt(view);
		final Block block = block(earlier, true);
		final List<Ballot> polka = new ArrayList<>();

		for (int validator = 1; validator <= OTHERS; validator++) {
			polka.add(ballot(PREVOTE, validator, earlier, block));
		}

		return isOwnView(view) ? Input.of(randomBallot(random, current))
			: Input.of(proposal(view, earlier, block, polka));
	}

	private Input randomTimeout(Random random, int current) {
		return new Input(null, new Timeout(Timeout.Kind.values()[random.nextInt(3)], 1, current), false);
	}

	/* The proposal of the view and the prevotes or precommits of the three others for its block: a polka or a
	   decision */
	private List<Input> burst(Random random, int current) {
		final Ballot.Type type = random.nextBoolean() ? PREVOTE : PRECOMMIT;
		final List<Input> inputs = new ArrayList<>();

		if (!isOwnView(current)) {
			inputs.add(Input.of(proposal(current, -1, block(current, true))));
		}

		for (int validator = 1; validator <= OTHERS; validator++) {
			inputs.add(Input.of(ballot(type, validator, current, block(current, true))));
		}

		return inputs;
	}

	private List<Input> randomInputs(Random random, int current) {
		return switch (random.nextInt(14)) {
			case 0, 1, 2 -> List.of(Input.of(randomBallot(random, current)));
			case 3, 4 -> List.of(randomProposal(random, current));
			case 5 -> List.of(randomReProposal(random, current));
			case 6, 7 -> List.of(randomTimeout(random, current));
			case 8 -> List.of(new Input(null, null, true));
			case 9 -> List.of(Input.of(ballot(PREVOTE, 1, FAR_AHEAD + random.nextInt(FAR_AHEAD), null)));
			default -> burst(random, current);
		};
	}

	private void remember(Run run, FakeApp app) {
		run.states().add(SignState.decode(run.reference().snapshot().encode(), validators.size()));
		run.goals().add(app.goal);
	}

	private Run referenceRun(Random random) {
		final FakeApp app = new FakeApp(validators, key(4), random.nextBoolean() ? 0 : WORK);
		final Run run = new Run(new Consensus(app, CHAIN, seed(4), TIMEOUTS), new ArrayList<>(), new ArrayList<>(),
			new ArrayList<>());

		run.reference().start(1, validators, AT);
		remember(run, app);

		while (run.script().size() < EVENTS) {
			for (final Input input : randomInputs(random, run.reference().view())) {
				run.script().add(input);
				input.feed(run.reference(), app);
				remember(run, app);
			}
		}

		return run;
	}

	/* The reference is restored after the first `split` events and sent what it had heard, as its peers would say it
	   again, and then the rest */
	private Consensus restoredAt(Run run, int split) {
		final FakeApp app = new FakeApp(validators, key(4), run.goals().get(split));
		final Consensus copy = new Consensus(app, CHAIN, seed(4), TIMEOUTS);
		final List<Input> heard = run.script().subList(0, split);

		copy.restore(run.states().get(split), validators, AT);
		heard.stream().filter(input -> input.message() != null).forEach(input -> input.feed(copy, app));
		run.script().subList(split, run.script().size()).forEach(input -> input.feed(copy, app));
		return copy;
	}

	private static Map<String, Message> signedBySlot(SignState state) {
		final Map<String, Message> slots = new TreeMap<>();

		state.proposals().forEach(proposal -> slots.put("proposal of view " + proposal.view(), proposal));
		state.ballots().forEach(ballot -> slots.put(ballot.type() + " of view " + ballot.view(), ballot));
		return slots;
	}

	/* What a ballot says is its block, and what a proposal says is its block and the view it was first put forward in.
	   The prevotes that prove it are in the order the proposer heard them, which differs between the two when the copy
	   proposes a view that the reference only proposes later in its run. */
	private static List<Object> said(Message message) {
		return switch (message) {
			case Proposal proposal -> List.of(ByteBuffer.wrap(proposal.block().hash()), proposal.validRound());
			case Ballot ballot -> List.of(ByteBuffer.wrap(ballot.blockHash()));
			case Decision decision -> List.of();
		};
	}

	private static void assertSameWhereBothSigned(Map<String, Message> reference, Map<String, Message> copy) {
		copy.forEach((slot, message) -> {
			if (reference.containsKey(slot)) {
				assertThat(slot, said(message), equalTo(said(reference.get(slot))));
			}
		});
	}

	@Property(tries = 30)
	public void shouldSignWhatAnEngineThatNeverStoppedSignsWhenRestoredAtAnyPoint(@ForAll long seed) {
		final Run run = referenceRun(new Random(seed));
		final SignState end = run.reference().snapshot();

		for (int split = 0; split <= run.script().size(); split++) {
			final Consensus copy = restoredAt(run, split);
			final Map<String, Message> keptThen = signedBySlot(run.states().get(split));
			final Map<String, Message> copySigned = signedBySlot(copy.snapshot());
			final String where = "restored after " + split + " events of seed " + seed;

			assertThat(where, copySigned.entrySet().containsAll(keptThen.entrySet()), equalTo(true));
			assertSameWhereBothSigned(signedBySlot(end), copySigned);
			assertThat(where, copy.snapshot().lockedBlock(), equalTo(end.lockedBlock()));
			assertThat(where, copy.snapshot().lockedView(), equalTo(end.lockedView()));
		}
	}
}
