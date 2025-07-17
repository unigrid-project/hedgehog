/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerVoteTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final AccountKey GRIDNODE = key(200);

	private static LedgerState freshState() {
		return state(1000, ANY_MINT, FOUNDATION);
	}

	private static Vote add(LedgerState state, int voter, AccountKey candidate) {
		return Vote.signed(seed(voter), candidate, Vote.Action.ADD, state.round(), state.chainId());
	}

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	@Example
	public void shouldAcceptAValidatorsVoteToAddAKey() {
		final LedgerState state = freshState();

		assertThat(state.rejectionOf(add(state, 1, GRIDNODE)).isPresent(), is(false));
		assertThat(state.votesFor(GRIDNODE, Vote.Action.ADD), equalTo(0));
	}

	@Example
	public void shouldCountTheVotesOfABlock() {
		final LedgerState state = freshState();

		state.apply(block(state, FOUNDATION, List.of(add(state, 1, GRIDNODE), add(state, 2, GRIDNODE))));

		assertThat(state.votesFor(GRIDNODE, Vote.Action.ADD), equalTo(2));
		assertThat(state.votesFor(GRIDNODE, Vote.Action.REMOVE), equalTo(0));
		assertThat(state.votesFor(key(201), Vote.Action.ADD), equalTo(0));
	}

	@Example
	public void shouldCommitTheRootToTheVotes() {
		final LedgerState state = freshState();
		final byte[] one = state.rootAfter(1, List.of(add(state, 1, GRIDNODE)));
		final byte[] other = state.rootAfter(1, List.of(add(state, 2, GRIDNODE)));

		assertThat(one, not(equalTo(state.stateRoot())));
		assertThat(one, not(equalTo(other)));
	}

	@Example
	public void shouldRefuseAVoteFromAKeyThatIsNoValidator() {
		final LedgerState state = freshState();

		assertThat(reasonOf(state.rejectionOf(add(state, 99, GRIDNODE))), containsString("not a validator"));
	}

	@Example
	public void shouldRefuseAVoteForAnotherRound() {
		final LedgerState state = freshState();
		final Vote later = Vote.signed(seed(1), GRIDNODE, Vote.Action.ADD, state.round() + 1, state.chainId());

		assertThat(reasonOf(state.rejectionOf(later)), containsString("another round"));
	}

	@Example
	public void shouldRefuseToAddAKeyThatIsAlreadyInTheSet() {
		final LedgerState state = freshState();

		assertThat(reasonOf(state.rejectionOf(add(state, 1, key(2)))), containsString("already a validator"));
	}

	@Example
	public void shouldRefuseToRemoveAKeyThatIsNotInTheSet() {
		final LedgerState state = freshState();
		final Vote remove = Vote.signed(seed(1), GRIDNODE, Vote.Action.REMOVE, state.round(), state.chainId());

		assertThat(reasonOf(state.rejectionOf(remove)), containsString("not a validator"));
	}

	/* The foundation machines are the backup and stay on whatever the vote says */
	@Example
	public void shouldRefuseToRemoveAFoundationValidator() {
		final LedgerState state = freshState();
		final Vote remove = Vote.signed(seed(1), key(2), Vote.Action.REMOVE, state.round(), state.chainId());

		assertThat(reasonOf(state.rejectionOf(remove)), containsString("Genesis validators"));
	}

	@Example
	public void shouldRefuseASecondVoteOnTheSameKeyInTheRound() {
		final LedgerState state = freshState();

		state.apply(block(state, FOUNDATION, List.of(add(state, 1, GRIDNODE))));

		assertThat(reasonOf(state.rejectionOf(add(state, 1, GRIDNODE))), containsString("already voted"));
		assertThat(state.rejectionOf(add(state, 2, GRIDNODE)).isPresent(), is(false));
		assertThat(state.rejectionOf(add(state, 1, key(201))).isPresent(), is(false));
	}

	@Example
	public void shouldRefuseTheSameVoteTwiceInOneBlock() {
		final LedgerState state = freshState();
		final List<Transaction> twice = List.of(add(state, 1, GRIDNODE), add(state, 1, GRIDNODE));

		assertThrows(IllegalArgumentException.class, () -> state.rootAfter(1, twice));
	}

	@Example
	public void shouldRefuseAVoteSignedForAnotherChain() {
		final LedgerState state = freshState();
		final byte[] otherChain = new byte[Digests.HASH_SIZE];
		final Vote foreign = Vote.signed(seed(1), GRIDNODE, Vote.Action.ADD, state.round(), otherChain);

		assertThat(reasonOf(state.rejectionOf(foreign)), containsString("signature"));
	}

	@Example
	public void shouldRefuseAVoteWithAnotherVotersSignature() {
		final LedgerState state = freshState();
		final Vote own = add(state, 1, GRIDNODE);
		final Vote forged = new Vote(Ed25519.publicKey(seed(2)), own.candidate(), own.action(), own.round(),
			own.signature());

		assertThat(reasonOf(state.rejectionOf(forged)), containsString("signature"));
	}
}
