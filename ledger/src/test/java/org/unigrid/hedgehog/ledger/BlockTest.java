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
import java.util.List;
import java.util.Random;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class BlockTest {
	private static byte[] seed(int first) {
		final byte[] seed = new byte[Ed25519.SEED_SIZE];

		seed[0] = (byte) first;
		return seed;
	}

	private static byte[] filled(int fill) {
		final byte[] bytes = new byte[Digests.HASH_SIZE];

		Arrays.fill(bytes, (byte) fill);
		return bytes;
	}

	private static Block.BlockBuilder builder() {
		return Block.builder().height(1).previousHash(filled(1)).time(10).stateRoot(filled(2))
			.transactionRoot(filled(3)).transactions(List.of()).proposer(Ed25519.publicKey(seed(1)))
			.endorsements(List.of());
	}

	@Example
	public void shouldHaveAHeaderOfFixedSize() {
		assertThat(builder().build().headerBytes().length, equalTo(Block.HEADER_SIZE));
	}

	@Example
	public void shouldHashEveryHeaderFieldButNotTheCertificate() {
		final byte[] base = builder().build().hash();

		assertThat(builder().build().endorsedBy(seed(1)).hash(), equalTo(base));
		assertThat(builder().height(2).build().hash(), not(equalTo(base)));
		assertThat(builder().previousHash(filled(9)).build().hash(), not(equalTo(base)));
		assertThat(builder().time(11).build().hash(), not(equalTo(base)));
		assertThat(builder().stateRoot(filled(9)).build().hash(), not(equalTo(base)));
		assertThat(builder().transactionRoot(filled(9)).build().hash(), not(equalTo(base)));
		assertThat(builder().proposer(Ed25519.publicKey(seed(9))).build().hash(), not(equalTo(base)));
	}

	@Example
	public void shouldEndorseWithASignatureOverItsHash() {
		final Block block = builder().build().endorsedBy(seed(2));
		final Endorsement endorsement = block.getEndorsements().get(0);

		assertThat(endorsement.signer(), equalTo(Ed25519.publicKey(seed(2))));
		assertThat(block.isSigned(endorsement), is(true));
		assertThat(builder().time(11).build().isSigned(endorsement), is(false));
	}

	@Property(tries = 50)
	public void shouldKeepTheCertificateSortedWhateverTheSigningOrder(@ForAll long order) {
		final List<byte[]> seeds = Arrays.asList(seed(1), seed(2), seed(3), seed(4));

		java.util.Collections.shuffle(seeds, new Random(order));

		Block block = builder().build();

		for (final byte[] seed : seeds) {
			block = block.endorsedBy(seed);
		}

		final List<AccountKey> signers = block.getEndorsements().stream().map(Endorsement::signer).toList();

		assertThat(signers, equalTo(signers.stream().sorted().toList()));
		assertThat(block, equalTo(builder().build().endorsedBy(seed(1)).endorsedBy(seed(2)).endorsedBy(seed(3))
			.endorsedBy(seed(4))));
	}

	@Example
	public void shouldRefuseToEndorseTwiceWithTheSameKey() {
		final Block block = builder().build().endorsedBy(seed(2));

		assertThrows(IllegalArgumentException.class, () -> block.endorsedBy(seed(2)));
	}

	@Example
	public void shouldRefuseHashesOfTheWrongSize() {
		assertThrows(IllegalArgumentException.class, () -> builder().previousHash(new byte[32]).build());
		assertThrows(IllegalArgumentException.class, () -> builder().stateRoot(new byte[32]).build());
		assertThrows(IllegalArgumentException.class, () -> builder().transactionRoot(new byte[32]).build());
	}

	@Example
	public void shouldRefuseAnEndorsementOfTheWrongSize() {
		assertThrows(IllegalArgumentException.class, () -> new Endorsement(Ed25519.publicKey(seed(1)), new byte[63]));
	}

	@Example
	public void shouldCompareEndorsementsByContent() {
		final Endorsement one = new Endorsement(Ed25519.publicKey(seed(1)), new byte[64]);

		assertThat(one, equalTo(new Endorsement(Ed25519.publicKey(seed(1)), new byte[64])));
		assertThat(one.hashCode(), equalTo(new Endorsement(Ed25519.publicKey(seed(1)), new byte[64]).hashCode()));
	}

	@Example
	public void shouldDeriveTheTransactionRootFromTheTransactionIds() {
		final Transaction mint = CodecFixtures.mint(new Random(1));

		assertThat(Block.transactionRootOf(List.of(mint)), equalTo(MerkleRoot.of(List.of(mint.id()))));
		assertThat(Block.transactionRootOf(List.of()), equalTo(MerkleRoot.of(List.of())));
	}
}
