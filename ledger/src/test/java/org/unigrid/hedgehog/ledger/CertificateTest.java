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
import java.util.Random;
import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class CertificateTest {
	private static Certificate certificate(Random random, int view, int endorsements) {
		return new Certificate(view, IntStream.range(0, endorsements).mapToObj(i -> new Endorsement(
			CodecFixtures.account(random), CodecFixtures.random(random, Ed25519.SIGNATURE_SIZE))).toList());
	}

	@Property(tries = 100)
	public void shouldRoundTrip(@ForAll long seed, @ForAll @IntRange(min = 0, max = 50) int view,
		@ForAll @IntRange(min = 0, max = 10) int endorsements) {
		final Certificate certificate = certificate(new Random(seed), view, endorsements);

		assertThat(Certificate.decode(ByteBuffer.wrap(certificate.encode())), equalTo(certificate));
		assertThat(certificate.encode().length,
			equalTo(Integer.BYTES + Short.BYTES + endorsements * Certificate.ENDORSEMENT_SIZE));
	}

	@Example
	public void shouldHashTheViewAndEveryEndorsement() {
		final Certificate base = certificate(new Random(1), 0, 3);

		assertThat(new Certificate(1, base.endorsements()).hash(), not(equalTo(base.hash())));
		assertThat(new Certificate(0, base.endorsements().subList(0, 2)).hash(), not(equalTo(base.hash())));
		assertThat(Certificate.EMPTY.hash().length, equalTo(Digests.HASH_SIZE));
		assertThat(Certificate.EMPTY.hash(), equalTo(new Certificate(0, List.of()).hash()));
	}

	@Property(tries = 50)
	public void shouldRefuseEveryTruncation(@ForAll long seed, @ForAll @IntRange(min = 0, max = 100000) int cutSeed) {
		final byte[] encoded = certificate(new Random(seed), 2, 3).encode();
		final int cut = cutSeed % encoded.length;

		assertThrows(IllegalArgumentException.class,
			() -> Certificate.decode(ByteBuffer.wrap(java.util.Arrays.copyOf(encoded, cut))));
	}

	@Example
	public void shouldRefuseANegativeViewAndTooManyEndorsements() {
		final byte[] encoded = certificate(new Random(1), 2, 1).encode();
		final byte[] negative = encoded.clone();
		final byte[] tooMany = encoded.clone();

		ByteBuffer.wrap(negative).putInt(0, -1);
		ByteBuffer.wrap(tooMany).putShort(Integer.BYTES, (short) (ValidatorSet.MAX_SIZE + 1));
		assertThrows(IllegalArgumentException.class, () -> Certificate.decode(ByteBuffer.wrap(negative)));
		assertThrows(IllegalArgumentException.class, () -> Certificate.decode(ByteBuffer.wrap(tooMany)));
	}

	@Example
	public void shouldRefuseToEncodeMoreEndorsementsThanItCanDecode() {
		final Certificate tooMany = certificate(new Random(1), 0, ValidatorSet.MAX_SIZE + 1);

		assertThrows(IllegalArgumentException.class, tooMany::encode);
		assertThrows(IllegalArgumentException.class, () -> new Certificate(-1, List.of()));
	}
}
