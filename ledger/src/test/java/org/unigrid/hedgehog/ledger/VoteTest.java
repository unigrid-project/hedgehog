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

import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class VoteTest {
	private static final byte[] SEED = new byte[Ed25519.SEED_SIZE];
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];
	private static final AccountKey CANDIDATE = Ed25519.publicKey(filled(7));

	private static byte[] filled(int fill) {
		final byte[] seed = new byte[Ed25519.SEED_SIZE];

		Arrays.fill(seed, (byte) fill);
		return seed;
	}

	private static Vote vote() {
		return Vote.signed(SEED, CANDIDATE, Vote.Action.ADD, 4, CHAIN);
	}

	@Property(tries = 100)
	public void shouldBeEqualByContent(@ForAll long seed) {
		final Vote vote = CodecFixtures.vote(new Random(seed));
		final Vote same = new Vote(vote.voter(), vote.candidate(), vote.action(), vote.round(), vote.signature());

		assertThat(vote, equalTo(same));
		assertThat(vote.hashCode(), equalTo(same.hashCode()));
		assertThat(vote.id(), equalTo(same.id()));
	}

	@Example
	public void shouldVerifyWhatItSigned() {
		assertThat(vote().hasValidSignature(CHAIN), is(true));
		assertThat(vote().voter(), equalTo(Ed25519.publicKey(SEED)));
	}

	/* Each field is under the signature, so a vote cannot be reused for another candidate, another action,
	   another round or another chain */
	@Example
	public void shouldBindTheSignatureToEveryField() {
		final Vote vote = vote();
		final byte[] otherChain = CHAIN.clone();

		otherChain[0] = 1;

		assertThat(vote.hasValidSignature(otherChain), is(false));
		assertThat(new Vote(vote.voter(), Ed25519.publicKey(filled(8)), vote.action(), vote.round(),
			vote.signature()).hasValidSignature(CHAIN), is(false));
		assertThat(new Vote(vote.voter(), vote.candidate(), Vote.Action.REMOVE, vote.round(), vote.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Vote(vote.voter(), vote.candidate(), vote.action(), vote.round() + 1, vote.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Vote(Ed25519.publicKey(filled(9)), vote.candidate(), vote.action(), vote.round(),
			vote.signature()).hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldEncodeKindThenFieldsInFixedWidths() {
		assertThat(vote().encode().length, equalTo(Vote.ENCODED_SIZE));
		assertThat(vote().encode()[0], equalTo(Vote.KIND));
		assertThat(vote().kind(), equalTo(Vote.KIND));
	}

	@Example
	public void shouldNameItsActionsByTheirWireByte() {
		assertThat(Vote.Action.of((byte) 1), equalTo(Vote.Action.ADD));
		assertThat(Vote.Action.of((byte) 2), equalTo(Vote.Action.REMOVE));
		assertThrows(IllegalArgumentException.class, () -> Vote.Action.of((byte) 0));
		assertThrows(IllegalArgumentException.class, () -> Vote.Action.of((byte) 3));
	}

	@Example
	public void shouldRefuseABadSignatureSizeOrANegativeRound() {
		assertThrows(IllegalArgumentException.class,
			() -> new Vote(vote().voter(), CANDIDATE, Vote.Action.ADD, 4, new byte[63]));
		assertThrows(IllegalArgumentException.class,
			() -> new Vote(vote().voter(), CANDIDATE, Vote.Action.ADD, -1, new byte[64]));
	}
}
