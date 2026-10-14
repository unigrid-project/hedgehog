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

import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class ClaimTest {
	private static final LegacyWallet WALLET = new LegacyWallet(3, true);
	private static final AccountKey RECIPIENT = new AccountKey(new byte[AccountKey.SIZE]);
	private static final byte[] CHAIN = new byte[Digests.HASH_SIZE];

	@Property(tries = 100)
	public void shouldBeEqualByContent(@ForAll long seed) {
		final Claim claim = CodecFixtures.claim(new Random(seed));
		final Claim same = new Claim(claim.publicKey(), claim.recipient(), claim.signature());

		assertThat(claim, equalTo(same));
		assertThat(claim.hashCode(), equalTo(same.hashCode()));
		assertThat(claim.id(), equalTo(same.id()));
	}

	@Example
	public void shouldEncodeKindKeyRecipientAndSignature() {
		final Claim compressed = CodecFixtures.claim(new Random(1), true);
		final Claim uncompressed = CodecFixtures.claim(new Random(1), false);

		assertThat(compressed.encode().length, equalTo(1 + 33 + AccountKey.SIZE + LegacySignature.SIZE));
		assertThat(uncompressed.encode().length, equalTo(Claim.MAX_ENCODED_SIZE));
		assertThat(compressed.encode()[0], equalTo(Claim.KIND));
		assertThat(compressed.kind(), equalTo(Claim.KIND));
	}

	@Example
	public void shouldRefuseAKeyThatIsNeitherCompressedNorUncompressed() {
		final byte[] wrongPrefix = new byte[33];
		final byte[] shortUncompressed = new byte[33];
		final byte[] longCompressed = new byte[65];

		wrongPrefix[0] = 5;
		shortUncompressed[0] = 4;
		longCompressed[0] = 2;

		for (final byte[] key : new byte[][] { new byte[0], wrongPrefix, shortUncompressed, longCompressed }) {
			assertThrows(IllegalArgumentException.class,
				() -> new Claim(key, RECIPIENT, new byte[LegacySignature.SIZE]));
		}
	}

	@Example
	public void shouldRefuseASignatureOfTheWrongSize() {
		assertThrows(IllegalArgumentException.class, () -> new Claim(WALLET.publicKey(), RECIPIENT, new byte[64]));
	}

	@Example
	public void shouldVerifyItsSignatureOnlyForTheChainItNames() {
		final Claim claim = WALLET.claim(RECIPIENT, CHAIN);
		final byte[] otherChain = new byte[Digests.HASH_SIZE];

		otherChain[0] = 1;

		assertThat(claim.hasValidSignature(CHAIN), is(true));
		assertThat(claim.hasValidSignature(otherChain), is(false));
	}

	/* The wallet signs this very text, so it is spelled out here and not built the way the code builds it */
	@Example
	public void shouldSpellTheMessageAsTheChainAndTheRecipientInHexAndStayShort() {
		final byte[] chain = new byte[Digests.HASH_SIZE];
		final byte[] recipient = new byte[AccountKey.SIZE];

		for (int i = 0; i < chain.length; i++) {
			chain[i] = (byte) i;
		}

		for (int i = 0; i < recipient.length; i++) {
			recipient[i] = (byte) (0x80 + i);
		}

		final String message = Claim.message(chain, new AccountKey(recipient));

		assertThat(message, equalTo("claim 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
			+ "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f "
			+ "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"));
		assertThat(message.length() <= 252, is(true));
	}

	@Example
	public void shouldGiveTheLegacyAddressOfItsKey() {
		assertThat(WALLET.claim(RECIPIENT, CHAIN).address(), equalTo(WALLET.address()));
	}

	@Example
	public void shouldGiveEveryFieldItsPartInTheId() {
		final Claim claim = WALLET.claim(RECIPIENT, CHAIN);
		final byte[] otherRecipient = new byte[AccountKey.SIZE];
		final byte[] otherSignature = claim.signature();

		otherRecipient[0] = 1;
		otherSignature[10] ^= 1;

		assertThat(new Claim(claim.publicKey(), new AccountKey(otherRecipient), claim.signature()).id(),
			not(equalTo(claim.id())));
		assertThat(new Claim(claim.publicKey(), RECIPIENT, otherSignature).id(), not(equalTo(claim.id())));
		assertThat(new Claim(new LegacyWallet(4, true).publicKey(), RECIPIENT, claim.signature()).id(),
			not(equalTo(claim.id())));
	}
}
