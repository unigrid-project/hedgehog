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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.bitcoinj.base.Base58;

/* A legacy address is 34 base58 characters starting with H: version 40, a hash160 and a 4 byte checksum */
public class LegacyAddressTest {
	private static final int ADDRESS_LENGTH = 34;

	/* The wallet address and public key the white paper gives for one domain */
	private static final String DOMAIN_ADDRESS = "HVdpXj25t7gPVdmxBC5kxbv9hojTncFg6P";
	private static final String DOMAIN_PUBLIC_KEY = "03cfb2a8ab7698f4955369f1ce46d918b64c50fda81c2bbabbee0163ec05e80d62";

	/* Correctly checksummed, but for the Bitcoin network */
	private static final String BITCOIN_ADDRESS = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";

	@Example
	public void shouldAcceptAnAddressFromTheWhitePaper() {
		assertThat(DOMAIN_ADDRESS, startsWith("H"));
		assertThat(DOMAIN_ADDRESS.length(), equalTo(ADDRESS_LENGTH));
		assertThat(LegacyAddress.decode(DOMAIN_ADDRESS).length, equalTo(Hashing.ADDRESS_HASH_SIZE));
	}

	@Example
	public void shouldDeriveTheWhitePaperAddressFromItsPublicKey() {
		final byte[] publicKey = HexFormat.of().parseHex(DOMAIN_PUBLIC_KEY);

		assertThat(LegacyAddress.encode(Hashing.addressHash(publicKey)), equalTo(DOMAIN_ADDRESS));
	}

	@Example
	public void shouldEncodeTheZeroHashAsTheKnownAddress() {
		assertThat(LegacyAddress.encode(new byte[Hashing.ADDRESS_HASH_SIZE]),
			equalTo("H6X8PLvXQDY3iLaTynKkQ1tUBBJjSZSf23"));
	}

	@Example
	public void shouldEncodeTheAllOnesHashAsTheKnownAddress() {
		final byte[] hash = new byte[Hashing.ADDRESS_HASH_SIZE];

		Arrays.fill(hash, (byte) 0xff);
		assertThat(LegacyAddress.encode(hash), equalTo("HVrjNTDp7PzvXmiZ1Cf4t9AFogZg5BbcAE"));
	}

	@Property(tries = 200)
	public void shouldRoundTripEveryHashAsThirtyFourCharactersStartingWithH(
		@ForAll @Size(Hashing.ADDRESS_HASH_SIZE) byte[] hash) {

		final String address = LegacyAddress.encode(hash);

		assertThat(address, startsWith("H"));
		assertThat(address.length(), equalTo(ADDRESS_LENGTH));
		assertThat(LegacyAddress.decode(address), equalTo(hash));
	}

	@Example
	public void shouldRejectAnAddressOfAnotherNetwork() {
		final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
			() -> LegacyAddress.decode(BITCOIN_ADDRESS)
		);

		assertThat(refusal.getMessage(), startsWith("Address is not for this network"));
	}

	@Property(tries = 50)
	public void shouldRejectACharacterOutsideTheAlphabet(@ForAll @IntRange(min = 0, max = ADDRESS_LENGTH - 1) int position,
		@ForAll("ambiguousCharacters") char ambiguous) {

		final String corrupted = DOMAIN_ADDRESS.substring(0, position) + ambiguous
			+ DOMAIN_ADDRESS.substring(position + 1);

		final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
			() -> LegacyAddress.decode(corrupted)
		);

		assertThat(refusal.getMessage(), startsWith("Not a base58 character"));
	}

	@Example
	public void shouldRejectAnEmptyAddress() {
		final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
			() -> LegacyAddress.decode("")
		);

		assertThat(refusal.getMessage(), startsWith("Address is too short"));
	}

	@Example
	public void shouldRejectAnOverlyLongAddressBeforeDecodingIt() {
		final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
			() -> LegacyAddress.decode("1".repeat(51))
		);

		assertThat(refusal.getMessage(), startsWith("Address is too long"));
	}

	@Example
	public void shouldRejectATamperedAddress() {
		final String tampered = DOMAIN_ADDRESS.substring(0, ADDRESS_LENGTH - 1) + "Z";
		final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
			() -> LegacyAddress.decode(tampered)
		);

		assertThat(refusal.getMessage(), startsWith("Address checksum does not match"));
	}

	@Property(tries = 200)
	public void shouldRejectAPayloadThatIsNotAHash160(@ForAll @Size(min = 1, max = 30) byte[] payload) {
		Assume.that(payload.length != Hashing.ADDRESS_HASH_SIZE);

		final String address = Base58.encodeChecked(LegacyAddress.PUBLIC_KEY_VERSION, payload);
		final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
			() -> LegacyAddress.decode(address)
		);

		assertThat(refusal.getMessage(), startsWith("Address payload is not a hash160"));
	}

	/* Base58 leaves these out because they are easily mistaken for other characters */
	@Provide
	public Arbitrary<Character> ambiguousCharacters() {
		return Arbitraries.of('0', 'O', 'I', 'l');
	}
}
