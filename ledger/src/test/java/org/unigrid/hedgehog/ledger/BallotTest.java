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

import java.nio.charset.StandardCharsets;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.key;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

public class BallotTest {
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];
	private static final byte[] HASH = Digests.sha512("block".getBytes(StandardCharsets.US_ASCII));

	@Example
	public void shouldVerifyForTheChainHeightViewAndHashItWasSignedFor() {
		for (final Ballot.Type type : Ballot.Type.values()) {
			final Ballot ballot = Ballot.signed(type, seed(1), CHAIN, 5, 2, HASH);
			final byte[] otherChain = new byte[Digests.HASH_SIZE];

			otherChain[0] = 1;

			assertThat(ballot.hasValidSignature(CHAIN), is(true));
			assertThat(ballot.hasValidSignature(otherChain), is(false));
			assertThat(new Ballot(type, 6, 2, HASH, ballot.validator(), ballot.signature())
				.hasValidSignature(CHAIN), is(false));
			assertThat(new Ballot(type, 5, 3, HASH, ballot.validator(), ballot.signature())
				.hasValidSignature(CHAIN), is(false));
			assertThat(new Ballot(type, 5, 2, Digests.sha512(HASH), ballot.validator(), ballot.signature())
				.hasValidSignature(CHAIN), is(false));
		}
	}

	@Example
	public void shouldNotLetAPrevoteBeUsedAsAPrecommit() {
		final Ballot prevote = Ballot.signed(Ballot.Type.PREVOTE, seed(1), CHAIN, 5, 2, HASH);
		final Ballot asPrecommit = new Ballot(Ballot.Type.PRECOMMIT, 5, 2, HASH, prevote.validator(),
			prevote.signature());

		assertThat(asPrecommit.hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldNotLetAPrecommitBeUsedAsAPrevote() {
		final Ballot precommit = Ballot.signed(Ballot.Type.PRECOMMIT, seed(1), CHAIN, 5, 2, HASH);
		final Ballot asPrevote = new Ballot(Ballot.Type.PREVOTE, 5, 2, HASH, precommit.validator(),
			precommit.signature());

		assertThat(asPrevote.hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldSignNothingAsTheZeroHashAndKnowIt() {
		final Ballot nil = Ballot.signed(Ballot.Type.PRECOMMIT, seed(1), CHAIN, 5, 0, null);

		assertThat(nil.isNil(), is(true));
		assertThat(nil.hasValidSignature(CHAIN), is(true));
		assertThat(Ballot.signed(Ballot.Type.PRECOMMIT, seed(1), CHAIN, 5, 0, HASH).isNil(), is(false));
	}

	@Example
	public void shouldMakeAnEndorsementOfAPrecommit() {
		final Ballot precommit = Ballot.signed(Ballot.Type.PRECOMMIT, seed(1), CHAIN, 5, 2, HASH);

		assertThat(Precommit.isSigned(precommit.endorsement(), CHAIN, 5, 2, HASH), is(true));
	}

	@Example
	public void shouldBeEqualByContent() {
		final Ballot ballot = Ballot.signed(Ballot.Type.PREVOTE, seed(1), CHAIN, 5, 2, HASH);
		final Ballot same = new Ballot(ballot.type(), 5, 2, ballot.blockHash(), ballot.validator(),
			ballot.signature());

		assertThat(ballot, equalTo(same));
		assertThat(ballot.hashCode(), equalTo(same.hashCode()));
		assertThat(Ballot.signed(Ballot.Type.PREVOTE, seed(2), CHAIN, 5, 2, HASH), not(equalTo(ballot)));
		assertThat(new Ballot(Ballot.Type.PRECOMMIT, 5, 2, HASH, ballot.validator(), ballot.signature()),
			not(equalTo(ballot)));
		assertThat(new Ballot(ballot.type(), 6, 2, HASH, ballot.validator(), ballot.signature()),
			not(equalTo(ballot)));
		assertThat(new Ballot(ballot.type(), 5, 3, HASH, ballot.validator(), ballot.signature()),
			not(equalTo(ballot)));
		assertThat(new Ballot(ballot.type(), 5, 2, Ballot.NIL, ballot.validator(), ballot.signature()),
			not(equalTo(ballot)));
	}

	@Example
	public void shouldRefuseWhatIsNotABallot() {
		assertThrows(IllegalArgumentException.class,
			() -> Ballot.signed(Ballot.Type.PREVOTE, seed(1), CHAIN, 0, 0, HASH));
		assertThrows(IllegalArgumentException.class,
			() -> Ballot.signed(Ballot.Type.PREVOTE, seed(1), CHAIN, 1, -1, HASH));
		assertThrows(IllegalArgumentException.class,
			() -> new Ballot(Ballot.Type.PREVOTE, 1, 0, new byte[3], key(1), new byte[Ed25519.SIGNATURE_SIZE]));
	}
}
