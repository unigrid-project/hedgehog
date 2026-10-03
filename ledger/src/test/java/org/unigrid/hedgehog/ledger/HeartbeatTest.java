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
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seed;

public class HeartbeatTest {
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];

	private static byte[] filled(int fill) {
		final byte[] bytes = new byte[Digests.HASH_SIZE];

		Arrays.fill(bytes, (byte) fill);
		return bytes;
	}

	private static Heartbeat heartbeat() {
		return Heartbeat.signed(seed(1), CHAIN, 7, filled(3), 5000);
	}

	@Example
	public void shouldVerifyWhatItSigned() {
		assertThat(heartbeat().hasValidSignature(CHAIN), is(true));
		assertThat(heartbeat().validator(), equalTo(Ed25519.publicKey(seed(1))));
		assertThat(heartbeat().height(), equalTo(7L));
		assertThat(heartbeat().time(), equalTo(5000L));
	}

	/* Each field is under the signature, so a heartbeat cannot be reused for another chain, tip, height or time */
	@Example
	public void shouldBindTheSignatureToEveryField() {
		final Heartbeat beat = heartbeat();
		final byte[] otherChain = CHAIN.clone();

		otherChain[0] = 1;

		assertThat(beat.hasValidSignature(otherChain), is(false));
		assertThat(new Heartbeat(beat.validator(), 8, beat.tipHash(), beat.time(), beat.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Heartbeat(beat.validator(), beat.height(), filled(4), beat.time(), beat.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Heartbeat(beat.validator(), beat.height(), beat.tipHash(), 5001, beat.signature())
			.hasValidSignature(CHAIN), is(false));
		assertThat(new Heartbeat(Ed25519.publicKey(seed(2)), beat.height(), beat.tipHash(), beat.time(),
			beat.signature()).hasValidSignature(CHAIN), is(false));
	}

	@Example
	public void shouldBeEqualByContent() {
		assertThat(heartbeat(), equalTo(heartbeat()));
		assertThat(heartbeat().hashCode(), equalTo(heartbeat().hashCode()));
	}

	@Example
	public void shouldRefuseTheWrongSizes() {
		assertThrows(IllegalArgumentException.class,
			() -> new Heartbeat(Ed25519.publicKey(seed(1)), 1, new byte[32], 1, new byte[64]));
		assertThrows(IllegalArgumentException.class,
			() -> new Heartbeat(Ed25519.publicKey(seed(1)), 1, filled(1), 1, new byte[63]));
		assertThrows(IllegalArgumentException.class,
			() -> new Heartbeat(Ed25519.publicKey(seed(1)), -1, filled(1), 1, new byte[64]));
	}
}
