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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class DigestsTest {
	private static final String ABC_SHA512 = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
		+ "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";

	/* The compressed public key of the secp256k1 generator, the key with private key 1, whose address hash is a
	   widely published constant */
	private static final String GENERATOR_KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
	private static final String GENERATOR_HASH160 = "751e76e8199196d454941c45d1b3a323f1433bd6";

	@Example
	public void shouldHashWithSha512() {
		assertThat(HexFormat.of().formatHex(Digests.sha512("abc".getBytes(StandardCharsets.US_ASCII))),
			equalTo(ABC_SHA512));
	}

	@Property(tries = 100)
	public void shouldHashPartsAsTheirConcatenation(@ForAll byte[] first, @ForAll byte[] second) {
		assertThat(Digests.sha512(first, second), equalTo(Digests.sha512(Bytes.concat(first, second))));
	}

	@Property(tries = 100)
	public void shouldHashTwiceWithSha256(@ForAll byte[] data) throws Exception {
		final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
		final byte[] expected = sha256.digest(sha256.digest(data));

		assertThat(Arrays.toString(Digests.sha256Twice(data)), equalTo(Arrays.toString(expected)));
	}

	@Example
	public void shouldProduceSixtyFourBytes() {
		assertThat(Digests.sha512().length, equalTo(Digests.HASH_SIZE));
	}

	@Example
	public void shouldDeriveAnAddressHashLikeTheLegacyChain() {
		assertThat(HexFormat.of().formatHex(Digests.hash160(HexFormat.of().parseHex(GENERATOR_KEY))),
			equalTo(GENERATOR_HASH160));
	}
}
