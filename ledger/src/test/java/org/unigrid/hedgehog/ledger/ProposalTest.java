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
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.key;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

public class ProposalTest {
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];

	private static Block block(long height, int view) {
		return Block.builder().height(height).view(view).previousHash(new byte[Digests.HASH_SIZE]).time(1)
			.stateRoot(new byte[Digests.HASH_SIZE]).transactionRoot(new byte[Digests.HASH_SIZE])
			.transactions(List.of()).proposer(key(1)).build();
	}

	@Example
	public void shouldVerifyOnlyForTheFieldsItWasSignedOver() {
		final Block block = block(4, 0);
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 4, 2, 0, block);
		final byte[] otherChain = new byte[Digests.HASH_SIZE];

		otherChain[0] = 1;

		assertThat(proposal.hasValidSignature(CHAIN), is(true));
		assertThat(proposal.hasValidSignature(otherChain), is(false));
		assertThat(new Proposal(4, 3, 0, block, proposal.sender(), proposal.signature()).hasValidSignature(CHAIN),
			is(false));
		assertThat(new Proposal(4, 2, 1, block, proposal.sender(), proposal.signature()).hasValidSignature(CHAIN),
			is(false));
		assertThat(new Proposal(4, 2, 0, block(4, 1), proposal.sender(), proposal.signature())
			.hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldBeEqualByContent() {
		final Block block = block(4, 0);
		final Proposal proposal = Proposal.signed(seed(1), CHAIN, 4, 2, 0, block);
		final Proposal same = new Proposal(4, 2, 0, block, proposal.sender(), proposal.signature());

		assertThat(proposal, equalTo(same));
		assertThat(proposal.hashCode(), equalTo(same.hashCode()));
		assertThat(Proposal.signed(seed(2), CHAIN, 4, 2, 0, block), not(equalTo(proposal)));
		assertThat(new Proposal(4, 3, 0, block, proposal.sender(), proposal.signature()), not(equalTo(proposal)));
		assertThat(new Proposal(4, 2, 1, block, proposal.sender(), proposal.signature()), not(equalTo(proposal)));
		assertThat(new Proposal(4, 2, 0, block(4, 1), proposal.sender(), proposal.signature()),
			not(equalTo(proposal)));
	}

	@Example
	public void shouldRefuseWhatIsNotAProposal() {
		final Block block = block(4, 0);

		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 5, 0, -1, block));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, -1, -1, block));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, 2, block));
		assertThrows(IllegalArgumentException.class, () -> Proposal.signed(seed(1), CHAIN, 4, 2, -2, block));
	}

	@Example
	public void shouldAllowTheViewOfTheValidRoundToBeAnyEarlierOne() {
		final Block block = block(4, 0);

		assertThat(Proposal.signed(seed(1), CHAIN, 4, 2, 1, block).validRound(), is(1));
		assertThat(Proposal.signed(seed(1), CHAIN, 4, 0, -1, block).validRound(), is(-1));
	}
}
