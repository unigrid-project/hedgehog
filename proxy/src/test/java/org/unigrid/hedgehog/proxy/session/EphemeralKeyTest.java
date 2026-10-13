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

package org.unigrid.hedgehog.proxy.session;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class EphemeralKeyTest {
	/* RFC 7748, section 6.1 */
	private static final String ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a";
	private static final String ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a";
	private static final String BOB_PRIVATE = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb";
	private static final String BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f";
	private static final String SHARED = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742";

	/* The points of low order, then p - 1, p and p + 1, and all of them again with the top bit set, which
	   X25519 ignores */
	private static final List<String> LOW_ORDER = List.of(
		"0000000000000000000000000000000000000000000000000000000000000000",
		"0100000000000000000000000000000000000000000000000000000000000000",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
		"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
		"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
		"0000000000000000000000000000000000000000000000000000000000000080",
		"0100000000000000000000000000000000000000000000000000000000000080",
		"e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b880",
		"5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f11d7",
		"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
		"edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
		"eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");

	/* RFC 7748, section 5.2 */
	private static final String FIRST_SCALAR = "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4";
	private static final String FIRST_POINT = "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c";
	private static final String FIRST_RESULT = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552";
	private static final String SECOND_SCALAR = "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d";
	private static final String SECOND_POINT = "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493";
	private static final String SECOND_RESULT = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957";
	private static final String BASE_POINT = "0900000000000000000000000000000000000000000000000000000000000000";
	private static final String AFTER_ONE_ITERATION = "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079";
	private static final String AFTER_A_THOUSAND_ITERATIONS
		= "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51";

	/* Alice's secret with the points 2, p - 2, 18 and p - 19, computed with a reference implementation of the
	   ladder in RFC 7748, section 5 and checked against openssl pkeyutl -derive */
	private static final String SECRET_WITH_TWO = "e80c0be9d3a1c5d71edd6316e8c9115ca35397cd47109bd38e32864f1adecf4d";
	private static final String SECRET_WITH_MINUS_TWO
		= "b328d18015068c80add31daed448f4bd6d2e3a5a451a41df9c83394a1e24ee13";
	private static final String SECRET_WITH_EIGHTEEN
		= "359668d79a67267a57ffef8f0f4a9882a7c0e3122cb1999c5626346383f9f811";
	private static final String SECRET_WITH_MINUS_NINETEEN
		= "4fc107b4e8847b8521fade96190160692c8399280ee4652ca1a59a3173608149";

	/* Encodings of the points 9, 2, p - 2, 18 and p - 19, which are not of low order although some of them lie
	   next to one and the others reach past the field prime p or set the top bit */
	private static final Map<String, String> ORDINARY_POINTS = Map.of(
		"f6ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", ALICE_PUBLIC,
		"f6ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", ALICE_PUBLIC,
		"0900000000000000000000000000000000000000000000000000000000000080", ALICE_PUBLIC,
		"efffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", SECRET_WITH_TWO,
		"efffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", SECRET_WITH_TWO,
		"ebffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", SECRET_WITH_MINUS_TWO,
		"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", SECRET_WITH_EIGHTEEN,
		"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", SECRET_WITH_EIGHTEEN,
		"daffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", SECRET_WITH_MINUS_NINETEEN);

	private static byte[] hex(String hex) {
		return HexFormat.of().parseHex(hex);
	}

	private static byte[] iterate(int times) throws HandshakeException {
		byte[] scalar = hex(BASE_POINT);
		byte[] point = scalar;

		for (int i = 0; i < times; i++) {
			final byte[] result = new EphemeralKey(scalar).agree(point);

			point = scalar;
			scalar = result;
		}

		return scalar;
	}

	@Example
	public void shouldMatchTheTestVectorsOfTheRfc() throws HandshakeException {
		final EphemeralKey alice = new EphemeralKey(hex(ALICE_PRIVATE));
		final EphemeralKey bob = new EphemeralKey(hex(BOB_PRIVATE));

		assertThat(alice.publicKey(), equalTo(hex(ALICE_PUBLIC)));
		assertThat(bob.publicKey(), equalTo(hex(BOB_PUBLIC)));
		assertThat(alice.agree(hex(BOB_PUBLIC)), equalTo(hex(SHARED)));
		assertThat(bob.agree(hex(ALICE_PUBLIC)), equalTo(hex(SHARED)));
	}

	@Example
	public void shouldMatchTheFunctionVectorsOfTheRfc() throws HandshakeException {
		assertThat(new EphemeralKey(hex(FIRST_SCALAR)).agree(hex(FIRST_POINT)), equalTo(hex(FIRST_RESULT)));
		assertThat(new EphemeralKey(hex(SECOND_SCALAR)).agree(hex(SECOND_POINT)), equalTo(hex(SECOND_RESULT)));
	}

	@Example
	public void shouldMatchTheIteratedVectorsOfTheRfc() throws HandshakeException {
		assertThat(iterate(1), equalTo(hex(AFTER_ONE_ITERATION)));
		assertThat(iterate(1000), equalTo(hex(AFTER_A_THOUSAND_ITERATIONS)));
	}

	@Property(tries = 100)
	public void shouldAgreeOnOneSecretFromEitherSide(@ForAll @Size(EphemeralKey.SIZE) byte[] client,
		@ForAll @Size(EphemeralKey.SIZE) byte[] exit) throws HandshakeException {
		final EphemeralKey clientKey = new EphemeralKey(client);
		final EphemeralKey exitKey = new EphemeralKey(exit);

		assertThat(clientKey.agree(exitKey.publicKey()), equalTo(exitKey.agree(clientKey.publicKey())));
	}

	@Example
	public void shouldRefuseEveryPointOfLowOrder() {
		final EphemeralKey key = EphemeralKey.generate(new SecureRandom());

		for (String point : LOW_ORDER) {
			Expect.assertThrows(HandshakeException.class, () -> key.agree(hex(point)));
		}
	}

	@Property(tries = 100)
	public void shouldRefuseEveryPointOfLowOrderForEveryKey(@ForAll @Size(EphemeralKey.SIZE) byte[] privateKey) {
		final EphemeralKey key = new EphemeralKey(privateKey);

		for (String point : LOW_ORDER) {
			Expect.assertThrows(HandshakeException.class, () -> key.agree(hex(point)));
		}
	}

	@Example
	public void shouldAcceptEveryEncodingOfAPointThatIsNotOfLowOrder() throws HandshakeException {
		final EphemeralKey alice = new EphemeralKey(hex(ALICE_PRIVATE));

		for (Map.Entry<String, String> point : ORDINARY_POINTS.entrySet()) {
			assertThat(alice.agree(hex(point.getKey())), equalTo(hex(point.getValue())));
		}
	}

	@Property(tries = 100)
	public void shouldIgnoreTheTopBitOfAPublicKey(@ForAll @Size(EphemeralKey.SIZE) byte[] mine,
		@ForAll @Size(EphemeralKey.SIZE) byte[] theirs) throws HandshakeException {
		final EphemeralKey key = new EphemeralKey(mine);
		final byte[] peer = new EphemeralKey(theirs).publicKey();
		final byte[] withTopBit = peer.clone();

		withTopBit[EphemeralKey.SIZE - 1] |= (byte) 0x80;
		assertThat(key.agree(withTopBit), equalTo(key.agree(peer)));
	}

	@Example
	public void shouldRefuseAPublicKeyOfTheWrongSize() {
		final EphemeralKey key = EphemeralKey.generate(new SecureRandom());

		Expect.assertThrows(HandshakeException.class, () -> key.agree(new byte[31]));
		Expect.assertThrows(HandshakeException.class, () -> key.agree(new byte[33]));
		Expect.assertThrows(IllegalArgumentException.class, () -> new EphemeralKey(new byte[16]));
	}

	@Example
	public void shouldRefuseAValidPublicKeyOneByteOffTheSize() {
		final EphemeralKey key = new EphemeralKey(hex(ALICE_PRIVATE));
		final byte[] shorter = hex(BOB_PUBLIC.substring(2));
		final byte[] longer = hex(BOB_PUBLIC + "00");

		Expect.assertThrows(HandshakeException.class, () -> key.agree(shorter));
		Expect.assertThrows(HandshakeException.class, () -> key.agree(longer));
		Expect.assertThrows(HandshakeException.class, () -> key.agree(new byte[0]));
	}

	@Property(tries = 100)
	public void shouldRefuseAPublicKeyOfAnotherSize(@ForAll @Size(max = 100) byte[] peer) {
		Assume.that(peer.length != EphemeralKey.SIZE);
		Expect.assertThrows(HandshakeException.class, () -> new EphemeralKey(hex(ALICE_PRIVATE)).agree(peer));
	}

	@Example
	public void shouldRefuseAPrivateKeyOneByteOffTheSize() {
		Expect.assertThrows(IllegalArgumentException.class, () -> new EphemeralKey(hex(ALICE_PRIVATE.substring(2))));
		Expect.assertThrows(IllegalArgumentException.class, () -> new EphemeralKey(hex(ALICE_PRIVATE + "00")));
		Expect.assertThrows(IllegalArgumentException.class, () -> new EphemeralKey(new byte[0]));
	}

	@Property(tries = 100)
	public void shouldRefuseAPrivateKeyOfAnotherSize(@ForAll @Size(max = 100) byte[] privateKey) {
		Assume.that(privateKey.length != EphemeralKey.SIZE);
		Expect.assertThrows(IllegalArgumentException.class, () -> new EphemeralKey(privateKey));
	}

	@Example
	public void shouldSayWhyAKeyIsRefused() {
		final EphemeralKey key = EphemeralKey.generate(new SecureRandom());
		final HandshakeException lowOrder = Expect.assertThrows(HandshakeException.class,
			() -> key.agree(hex(LOW_ORDER.get(0))));
		final HandshakeException wrongSize = Expect.assertThrows(HandshakeException.class,
			() -> key.agree(new byte[31]));

		assertThat(lowOrder.getMessage(), containsString("low order"));
		assertThat(wrongSize.getMessage(), allOf(containsString("32"), containsString("31")));
	}

	@Example
	public void shouldKeepWorkingAfterARefusal() throws HandshakeException {
		final EphemeralKey alice = new EphemeralKey(hex(ALICE_PRIVATE));

		Expect.assertThrows(HandshakeException.class, () -> alice.agree(hex(LOW_ORDER.get(0))));
		Expect.assertThrows(HandshakeException.class, () -> alice.agree(new byte[31]));
		assertThat(alice.agree(hex(BOB_PUBLIC)), equalTo(hex(SHARED)));
		assertThat(alice.publicKey(), equalTo(hex(ALICE_PUBLIC)));
	}

	@Example
	public void shouldNotKeepTheArrayItWasGiven() throws HandshakeException {
		final byte[] privateKey = hex(ALICE_PRIVATE);
		final EphemeralKey alice = new EphemeralKey(privateKey);

		Arrays.fill(privateKey, (byte) 0);
		assertThat(alice.publicKey(), equalTo(hex(ALICE_PUBLIC)));
		assertThat(alice.agree(hex(BOB_PUBLIC)), equalTo(hex(SHARED)));
	}

	@Example
	public void shouldHandOutArraysThatDoNotChangeTheKey() throws HandshakeException {
		final EphemeralKey alice = new EphemeralKey(hex(ALICE_PRIVATE));
		final byte[] publicKey = alice.publicKey();
		final byte[] peer = hex(BOB_PUBLIC);
		final byte[] secret = alice.agree(peer);

		Arrays.fill(publicKey, (byte) 0);
		Arrays.fill(secret, (byte) 0);
		assertThat(peer, equalTo(hex(BOB_PUBLIC)));
		assertThat(alice.publicKey(), equalTo(hex(ALICE_PUBLIC)));
		assertThat(alice.agree(peer), equalTo(hex(SHARED)));
	}

	@Example
	public void shouldKeepEarlierSecretsWhenAgreeingAgain() throws HandshakeException {
		final EphemeralKey alice = new EphemeralKey(hex(ALICE_PRIVATE));
		final byte[] first = alice.agree(hex(BOB_PUBLIC));

		alice.agree(hex(FIRST_POINT));
		assertThat(first, equalTo(hex(SHARED)));
	}

	@Example
	public void shouldNeverExposeThePrivateKeyThroughToStringOrEquals() {
		final byte[] privateKey = hex(ALICE_PRIVATE);
		final EphemeralKey key = new EphemeralKey(privateKey);
		final EphemeralKey sameKey = new EphemeralKey(privateKey);

		assertThat(key.toString(), is(EphemeralKey.class.getName() + "@" + Integer.toHexString(key.hashCode())));
		assertThat(key.hashCode(), is(System.identityHashCode(key)));
		assertThat(key.equals(key), is(true));
		assertThat(key.equals(sameKey), is(false));
	}

	@Example
	public void shouldGenerateAFreshKeyEveryTime() {
		final SecureRandom random = new SecureRandom();

		assertThat(EphemeralKey.generate(random).publicKey(),
			not(equalTo(EphemeralKey.generate(random).publicKey())));
		assertThat(EphemeralKey.generate(random).publicKey().length, is(EphemeralKey.SIZE));
	}

	@Example
	public void shouldDrawTheKeyFromTheRandomItIsGiven() {
		final SecureRandom random = new FixedRandom(hex(ALICE_PRIVATE));

		assertThat(EphemeralKey.generate(random).publicKey(), equalTo(hex(ALICE_PUBLIC)));
	}

	private static final class FixedRandom extends SecureRandom {
		private final byte[] bytes;

		FixedRandom(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public void nextBytes(byte[] target) {
			assertThat(target.length, is(bytes.length));
			System.arraycopy(bytes, 0, target, 0, bytes.length);
		}
	}
}
