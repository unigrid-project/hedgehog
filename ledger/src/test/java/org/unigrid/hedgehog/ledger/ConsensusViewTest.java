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

import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Action.*;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.*;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class ConsensusViewTest {
	private final ValidatorSet validators = validators(4);
	private final FakeApp app = new FakeApp(validators, key(2), 3);
	private final Consensus engine = new Consensus(app, CHAIN, seed(2), Timeouts.DEFAULT);

	private Block proposed(int view, String note) {
		return app.block(1, view, validators.proposerAt(1, view), 100, note);
	}

	private List<Action> precommitQuorum(int view, Block block) {
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, view, block), 20);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, view, block), 21);
		return engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, view, block), 22);
	}

	@Example
	public void shouldPrevoteTheBlockOfTheProposerOfTheView() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);

		final List<Ballot> out = ballots(engine.onMessage(proposal(0, -1, block), 10));

		assertThat(out.size(), equalTo(1));
		assertThat(out.get(0).type(), equalTo(Ballot.Type.PREVOTE));
		assertThat(out.get(0).blockHash(), equalTo(block.hash()));
		assertThat(engine.step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldPrevoteNilForABlockTheApplicationRefuses() {
		app.refuse = true;
		engine.start(1, validators, 0);

		final List<Ballot> out = ballots(engine.onMessage(proposal(0, -1, proposed(0, "a")), 10));

		assertThat(out.get(0).isNil(), is(true));
	}

	@Example
	public void shouldIgnoreAProposalNotFromTheProposerOfTheView() {
		engine.start(1, validators, 0);

		final Block block = proposed(0, "a");
		final Proposal wrong = Proposal.signed(seed(3), CHAIN, 1, 0, -1, block);

		assertThat(engine.onMessage(wrong, 10).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
	}

	@Example
	public void shouldIgnoreAProposalThatIsNotRightAndStillTakeTheRealOne() {
		final Block block = proposed(0, "a");
		final Proposal forged = new Proposal(1, 0, -1, block, key(1), new byte[64]);
		final Proposal otherChain = Proposal.signed(seed(1), new byte[Digests.HASH_SIZE], 1, 0, -1, block);
		final Proposal otherHeight = Proposal.signed(seed(1), CHAIN, 2, 0, -1, app.block(2, 0, key(1), 100, "a"));

		engine.start(1, validators, 0);

		assertThat(engine.onMessage(forged, 10).isEmpty(), is(true));
		assertThat(engine.onMessage(otherChain, 10).isEmpty(), is(true));
		assertThat(engine.onMessage(otherHeight, 10).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
		assertThat(ballots(engine.onMessage(proposal(0, -1, block), 11)).size(), equalTo(1));
	}

	@Example
	public void shouldIgnoreAProposalWhoseBlockHeaderNamesAnotherView() {
		final Block elsewhere = app.block(1, 3, validators.proposerAt(1, 3), 100, "a");

		engine.start(1, validators, 0);

		assertThat(engine.onMessage(proposal(0, -1, elsewhere), 10).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
		assertThat(ballots(engine.onMessage(proposal(0, -1, proposed(0, "a")), 11)).size(), equalTo(1));
	}

	@Example
	public void shouldIgnoreANewBlockWhoseHeaderIsOfAnEarlierViewThanItsProposal() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(1, -1, block), 10);

		assertThat(decides(precommitQuorum(1, block)), is(false));
	}

	@Example
	public void shouldIgnoreAPutForwardBlockWhoseHeaderIsOfALaterViewThanItsValidRound() {
		final Block block = proposed(2, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(2, 1, block), 10);

		assertThat(decides(precommitQuorum(2, block)), is(false));
	}

	@Example
	public void shouldTakeAPutForwardBlockWhoseHeaderIsOfTheValidRound() {
		final Block block = proposed(1, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(2, 1, block), 10);

		assertThat(decidedBlock(precommitQuorum(2, block)).hash(), equalTo(block.hash()));
	}

	@Example
	public void shouldTakeAPutForwardBlockWhoseHeaderIsOfAnEarlierViewThanItsValidRound() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(2, 1, block), 10);

		assertThat(decidedBlock(precommitQuorum(2, block)).hash(), equalTo(block.hash()));
	}

	@Example
	public void shouldPrecommitAndLockOnMoreThanTwoThirdsOfPrevotes() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, block), 11);

		final List<Ballot> out = ballots(engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, block), 12));

		assertThat(out.size(), equalTo(1));
		assertThat(out.get(0).type(), equalTo(Ballot.Type.PRECOMMIT));
		assertThat(out.get(0).blockHash(), equalTo(block.hash()));
		assertThat(engine.step(), equalTo(Consensus.Step.PRECOMMIT));
		assertThat(engine.lockedView(), equalTo(0));
	}

	@Example
	public void shouldPrecommitOnlyOnceOnAQuorumOfPrevotes() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, block), 12);

		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 4, 0, block), 13).isEmpty(), is(true));
		assertThat(engine.lockedView(), equalTo(0));
	}

	@Example
	public void shouldPrevoteAndLockAtOnceWhenTheProposalIsTheLastToArrive() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, block), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, block), 11);
		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 4, 0, block), 12).isEmpty(), is(true));

		final List<Ballot> out = ballots(engine.onMessage(proposal(0, -1, block), 13));

		assertThat(out.size(), equalTo(2));
		assertThat(out.get(0).type(), equalTo(Ballot.Type.PREVOTE));
		assertThat(out.get(1).type(), equalTo(Ballot.Type.PRECOMMIT));
		assertThat(engine.lockedView(), equalTo(0));
	}

	@Example
	public void shouldNotLockOnABlockTheApplicationRefuses() {
		final Block block = proposed(0, "a");

		app.refuse = true;
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, block), 12);

		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 4, 0, block), 13).isEmpty(), is(true));
		assertThat(engine.lockedView(), equalTo(-1));
		assertThat(engine.step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldPrecommitNilOnMoreThanTwoThirdsOfNilPrevotes() {
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, proposed(0, "a")), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, null), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, null), 12);

		final List<Ballot> out = ballots(engine.onMessage(ballot(Ballot.Type.PREVOTE, 4, 0, null), 13));

		assertThat(out.stream().anyMatch(b -> b.type() == Ballot.Type.PRECOMMIT && b.isNil()), is(true));
		assertThat(engine.lockedView(), equalTo(-1));
	}

	@Example
	public void shouldNotPrecommitNilBeforeItHasPrevoted() {
		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, null), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, null), 12);

		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 4, 0, null), 13).isEmpty(), is(true));
		assertThat(engine.step(), equalTo(Consensus.Step.PROPOSE));
	}

	@Example
	public void shouldDecideOnMoreThanTwoThirdsOfPrecommitsAndHandBackACertifiedBlock() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 12);

		final List<Action> actions = engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, block), 13);
		final Block decided = decidedBlock(actions);

		assertThat(decided.hash(), equalTo(block.hash()));
		assertThat(decided.getCertificate().view(), equalTo(0));
		assertThat(app.validateCertified(decided).isEmpty(), is(true));
		assertThat(actions.stream().anyMatch(a -> a instanceof Broadcast b && b.message() instanceof Decision),
			is(true));
	}

	@Example
	public void shouldNotDecideWithoutTheBlockOfTheQuorum() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 12);

		final List<Action> actions = engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, block), 13);

		assertThat(decides(actions), is(false));
		assertThat(decides(engine.onMessage(proposal(0, -1, block), 14)), is(true));
	}

	@Example
	public void shouldDecideInAViewOtherThanTheCurrentOne() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 2, null), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 2, null), 12);

		assertThat(engine.view(), equalTo(2));

		final Block decided = decidedBlock(precommitQuorum(0, block));

		assertThat(engine.view(), equalTo(2));
		assertThat(decided.hash(), equalTo(block.hash()));
		assertThat(decided.getCertificate().view(), equalTo(0));
		assertThat(app.validateCertified(decided).isEmpty(), is(true));
	}

	@Example
	public void shouldDecideInTheLastViewOfTheWindow() {
		final Block block = proposed(Consensus.MAX_VIEW_AHEAD, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(Consensus.MAX_VIEW_AHEAD, -1, block), 10);

		final Block decided = decidedBlock(precommitQuorum(Consensus.MAX_VIEW_AHEAD, block));

		assertThat(decided.getCertificate().view(), equalTo(Consensus.MAX_VIEW_AHEAD));
		assertThat(app.validateCertified(decided).isEmpty(), is(true));
	}

	@Example
	public void shouldKeepNothingOfAViewBeyondTheWindow() {
		final int beyond = Consensus.MAX_VIEW_AHEAD + 1;
		final Block block = proposed(beyond, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(beyond, -1, block), 10);

		assertThat(decides(precommitQuorum(beyond, block)), is(false));
	}

	@Example
	public void shouldNotDecideABlockTheApplicationRefuses() {
		final Block block = proposed(0, "a");

		app.refuse = true;
		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 12);

		assertThat(decides(engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, block), 13)), is(false));
	}

	@Example
	public void shouldKeepTheFirstProposalOfAViewWhenTheProposerSendsTwo() {
		final Block first = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, first), 10);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, first), 11);
		engine.onMessage(ballot(Ballot.Type.PREVOTE, 3, 0, first), 12);

		assertThat(engine.lockedView(), equalTo(0));
		assertThat(engine.onMessage(proposal(0, -1, proposed(0, "b")), 13).isEmpty(), is(true));
		engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, first), 14);

		final List<Action> actions = engine.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, first), 15);

		assertThat(decidedBlock(actions).hash(), equalTo(first.hash()));
	}

	@Example
	public void shouldDecideOnACertifiedDecisionOnly() {
		final Block block = proposed(0, "a");
		final Block thin = block.endorsedBy(seed(1), CHAIN).endorsedBy(seed(3), CHAIN);
		final Block certified = thin.endorsedBy(seed(4), CHAIN);

		engine.start(1, validators, 0);

		assertThat(engine.onMessage(new Decision(thin), 5).isEmpty(), is(true));

		final List<Action> actions = engine.onMessage(new Decision(certified), 6);

		assertThat(decidedBlock(actions).hash(), equalTo(block.hash()));
		assertThat(actions.stream().anyMatch(a -> a instanceof Broadcast b && b.message() instanceof Decision),
			is(true));
		assertThat(engine.onMessage(new Decision(certified), 7).isEmpty(), is(true));
	}

	@Example
	public void shouldIgnoreADecisionOfAnotherHeight() {
		final Block block = app.block(2, 0, key(2), 100, "a");

		engine.start(1, validators, 0);

		assertThat(engine.onMessage(new Decision(block.endorsedBy(seed(1), CHAIN).endorsedBy(seed(2), CHAIN)
			.endorsedBy(seed(3), CHAIN)), 5).isEmpty(), is(true));
	}

	@Example
	public void shouldIgnoreBallotsThatAreNotRightAndCountEachValidatorOnce() {
		final Block block = proposed(0, "a");

		engine.start(1, validators, 0);
		engine.onMessage(proposal(0, -1, block), 10);

		final Ballot forged = new Ballot(Ballot.Type.PREVOTE, 1, 0, block.hash(), key(1), new byte[64]);
		final Ballot stranger = Ballot.signed(Ballot.Type.PREVOTE, seed(9), CHAIN, 1, 0, block.hash());
		final Ballot otherHeight = Ballot.signed(Ballot.Type.PREVOTE, seed(1), CHAIN, 2, 0, block.hash());

		assertThat(engine.onMessage(forged, 11).isEmpty(), is(true));
		assertThat(engine.onMessage(stranger, 11).isEmpty(), is(true));
		assertThat(engine.onMessage(otherHeight, 11).isEmpty(), is(true));

		final Ballot once = ballot(Ballot.Type.PREVOTE, 1, 0, block);

		engine.onMessage(once, 12);
		assertThat(engine.onMessage(once, 13).isEmpty(), is(true));
		assertThat(engine.onMessage(ballot(Ballot.Type.PREVOTE, 1, 0, null), 14).isEmpty(), is(true));
		assertThat(engine.evidence().size(), equalTo(1));
		assertThat(engine.step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldNotBroadcastAnythingWhenItHasNoKey() {
		final Consensus observer = new Consensus(app, CHAIN, null, Timeouts.DEFAULT);
		final Block block = proposed(0, "a");

		observer.start(1, validators, 0);
		observer.onMessage(proposal(0, -1, block), 10);

		final List<Action> actions = observer.onMessage(ballot(Ballot.Type.PRECOMMIT, 1, 0, block), 11);

		assertThat(actions.isEmpty(), is(true));

		observer.onMessage(ballot(Ballot.Type.PRECOMMIT, 3, 0, block), 12);

		final List<Action> last = observer.onMessage(ballot(Ballot.Type.PRECOMMIT, 4, 0, block), 13);

		assertThat(decides(last), is(true));
		assertThat(last.stream().noneMatch(a -> a instanceof Broadcast b && b.message() instanceof Ballot), is(true));
	}

	@Example
	public void shouldNotVoteWhenItsKeyIsNotInTheValidatorSet() {
		final Consensus outsider = new Consensus(app, CHAIN, seed(9), Timeouts.DEFAULT);

		outsider.start(1, validators, 0);

		assertThat(outsider.onMessage(proposal(0, -1, proposed(0, "a")), 10).isEmpty(), is(true));
		assertThat(outsider.step(), equalTo(Consensus.Step.PREVOTE));
	}

	@Example
	public void shouldProposeAndPrevoteAtOnceWhenItProposesTheFirstViewAndHasWork() {
		final FakeApp mine = new FakeApp(validators, key(1), 3);
		final Consensus proposer = new Consensus(mine, CHAIN, seed(1), Timeouts.DEFAULT);

		final List<Action> actions = proposer.start(1, validators, 0);
		final Proposal sent = proposalsSent(actions).get(0);
		final List<Ballot> out = ballots(actions);

		assertThat(proposalsSent(actions).size(), equalTo(1));
		assertThat(sent.view(), equalTo(0));
		assertThat(sent.validRound(), equalTo(-1));
		assertThat(sent.hasValidSignature(CHAIN), is(true));
		assertThat(sent.block().hash(), equalTo(mine.propose(1, 0, 0).orElseThrow().hash()));
		assertThat(out.size(), equalTo(1));
		assertThat(out.get(0).blockHash(), equalTo(sent.block().hash()));
	}

	@Example
	public void shouldStayIdleWithoutWorkUntilAMessageThatIsRightArrives() {
		final FakeApp idle = new FakeApp(validators, key(1), 0);
		final Consensus proposer = new Consensus(idle, CHAIN, seed(1), Timeouts.DEFAULT);

		assertThat(proposer.start(1, validators, 0).isEmpty(), is(true));

		idle.goal = 3;

		final Ballot forged = new Ballot(Ballot.Type.PREVOTE, 1, 0, new byte[Digests.HASH_SIZE], key(2),
			new byte[64]);
		final Ballot forgedAhead = new Ballot(Ballot.Type.PREVOTE, 1, 1000, new byte[Digests.HASH_SIZE], key(2),
			new byte[64]);
		final Ballot strangerAhead = Ballot.signed(Ballot.Type.PREVOTE, seed(9), CHAIN, 1, 1000, null);

		assertThat(proposer.onMessage(forged, 5).isEmpty(), is(true));
		assertThat(proposer.onMessage(forgedAhead, 5).isEmpty(), is(true));
		assertThat(proposer.onMessage(strangerAhead, 5).isEmpty(), is(true));
		assertThat(proposalsSent(proposer.onMessage(ballot(Ballot.Type.PREVOTE, 2, 1000, null), 6)).size(),
			equalTo(1));
	}
}
