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

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.CharRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.util.BigIntegers;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class LegacySignatureTest {
	private static final String MESSAGE = "my message";

	@Example
	public void shouldAcceptTheSignatureOfTheOwnerForBothKeyEncodings() {
		for (final boolean compressed : new boolean[] { true, false }) {
			final LegacyWallet wallet = new LegacyWallet(7, compressed);

			assertThat(LegacySignature.verify(wallet.publicKey(), wallet.sign(MESSAGE), MESSAGE), is(true));
		}
	}

	@Example
	public void shouldGiveTheKnownAddressesOfThePrivateKeyOne() {
		assertThat(new LegacyWallet(1, true).address(),
			equalTo(new LegacyAddress(HexFormat.of().parseHex("751e76e8199196d454941c45d1b3a323f1433bd6"))));
		assertThat(new LegacyWallet(1, false).address(),
			equalTo(new LegacyAddress(HexFormat.of().parseHex("91b24bf9f5288532960ac687abb035127b1d28a5"))));
	}

	@Example
	public void shouldTreatTheTwoEncodingsOfOneKeyAsTwoAddresses() {
		assertThat(new LegacyWallet(7, true).address(), not(equalTo(new LegacyWallet(7, false).address())));
	}

	@Example
	public void shouldRefuseAnotherMessageAndAnotherKey() {
		final LegacyWallet wallet = new LegacyWallet(7, true);
		final byte[] signature = wallet.sign(MESSAGE);

		assertThat(LegacySignature.verify(wallet.publicKey(), signature, "other message"), is(false));
		assertThat(LegacySignature.verify(new LegacyWallet(8, true).publicKey(), signature, MESSAGE), is(false));
	}

	@Example
	public void shouldRefuseAHighS() {
		final LegacyWallet wallet = new LegacyWallet(7, true);
		final byte[] signature = wallet.sign(MESSAGE);
		final byte[] malleated = LegacyWallet.withS(signature,
			LegacySignature.CURVE.getN().subtract(LegacyWallet.sOf(signature)));

		assertThat(LegacySignature.verify(wallet.publicKey(), malleated, MESSAGE), is(false));
	}

	@Example
	public void shouldRefuseAHeaderThatDoesNotFitTheKey() {
		final LegacyWallet wallet = new LegacyWallet(7, true);

		for (final int header : new int[] { 27, 26, 35, 0 }) {
			final byte[] signature = wallet.sign(MESSAGE);

			signature[0] = (byte) header;
			assertThat("header " + header, LegacySignature.verify(wallet.publicKey(), signature, MESSAGE), is(false));
		}
	}

	@Example
	public void shouldAnswerFalseToWhatIsMalformed() {
		final LegacyWallet wallet = new LegacyWallet(7, true);
		final byte[] offTheCurve = new byte[33];

		offTheCurve[0] = 2;
		Arrays.fill(offTheCurve, 1, 33, (byte) 0xff);

		assertThat(LegacySignature.verify(wallet.publicKey(), new byte[64], MESSAGE), is(false));
		assertThat(LegacySignature.verify(offTheCurve, wallet.sign(MESSAGE), MESSAGE), is(false));
		assertThat(LegacySignature.verify(wallet.publicKey(), new byte[LegacySignature.SIZE], MESSAGE), is(false));
	}

	@Example
	public void shouldAnswerFalseToAKeyOfTheWrongSize() {
		final LegacyWallet compressed = new LegacyWallet(7, true);
		final LegacyWallet uncompressed = new LegacyWallet(7, false);

		for (final int size : new int[] { 0, 1, 32, 34, 64, 66 }) {
			assertThat("size " + size, LegacySignature.verify(new byte[size], compressed.sign(MESSAGE), MESSAGE),
				is(false));
			assertThat("size " + size, LegacySignature.verify(new byte[size], uncompressed.sign(MESSAGE), MESSAGE),
				is(false));
		}
	}

	@Example
	public void shouldRefuseAHybridKeyThatHashesToAnotherAddress() {
		final LegacyWallet wallet = new LegacyWallet(7, false);
		final byte[] signature = wallet.sign(MESSAGE);

		for (final byte prefix : new byte[] { 6, 7 }) {
			final byte[] hybrid = wallet.publicKey();

			hybrid[0] = prefix;
			assertThat("prefix " + prefix, LegacySignature.verify(hybrid, signature, MESSAGE), is(false));
		}
	}

	@Example
	public void shouldRefuseAKeyWithAnUnknownPrefixOfTheRightSize() {
		for (final boolean compressed : new boolean[] { true, false }) {
			final LegacyWallet wallet = new LegacyWallet(7, compressed);
			final byte[] key = wallet.publicKey();

			key[0] = 5;
			assertThat(LegacySignature.verify(key, wallet.sign(MESSAGE), MESSAGE), is(false));
		}
	}

	@Example
	public void shouldRefuseACharacterThatAsciiCannotCarry() {
		final LegacyWallet wallet = new LegacyWallet(7, true);

		assertThrows(IllegalArgumentException.class, () -> LegacySignature.messageHash("café"));
		assertThat(LegacySignature.verify(wallet.publicKey(), wallet.sign("caf?"), "café"), is(false));
	}

	@Example
	public void shouldHashTheMagicAndTheMessageEachLedByItsLength() throws NoSuchAlgorithmException {
		final MessageDigest sha = MessageDigest.getInstance("SHA-256");
		final byte[] magic = "DarkNet Signed Message:\n".getBytes(StandardCharsets.US_ASCII);
		final byte[] text = MESSAGE.getBytes(StandardCharsets.US_ASCII);
		final byte[] expected = sha.digest(sha.digest(Bytes.concat(new byte[] { (byte) magic.length }, magic,
			new byte[] { (byte) text.length }, text)));

		assertThat(LegacySignature.messageHash(MESSAGE), equalTo(expected));
	}

	/* Made outside this code: the hash with hashlib from the formula of the wallet, the signature with OpenSSL (s
	   made low, header 32 from the recovery id found by recovering the key), so a change in how the hash is built or
	   how a signature is read fails here and not against a real wallet. The text is that of a claim. */
	@Example
	public void shouldAcceptASignatureMadeByAnIndependentImplementation() {
		final String text = "claim 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
			+ "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f "
			+ "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f";
		final byte[] publicKey = HexFormat.of()
			.parseHex("03b7a54a585232eb13d78fe2c8724d2f54d44ef191da6db6024588b89bbce4f893");
		final byte[] signature = HexFormat.of()
			.parseHex("206bb1c205594abdf713698aea2bd5937810978ae3f854fe0b9d488af16293f3"
				+ "343e4622f691864c24107266e817d6285eb5cedb598de44aa07c0dff1abf84f2cc");

		assertThat(LegacySignature.messageHash(text), equalTo(
			HexFormat.of().parseHex("d56f2842c39980121551f9d40fed5e42d862bf826b34b5770dfb3fdd21af1896")));
		assertThat(LegacySignature.verify(publicKey, signature, text), is(true));
		assertThat(LegacySignature.recover(text, signature).orElseThrow(), equalTo(publicKey));
	}

	/* Made by the signing code of the legacy wallet itself, the path of its signmessage call (CHashWriter over the
	   magic and the text, CKey::SignCompact on libsecp256k1), for both encodings of one key, and kept as the wallet
	   prints it, in base64. The text is the claim of an account on a chain. */
	@Example
	public void shouldAcceptTheClaimOfASignatureMadeByTheLegacyWallet() {
		final byte[] chainId = HexFormat.of().parseHex("def0b9a3cdc4f37149f7b4616748a5d428fed991fb5ed33ddbeffb3af5376cca"
			+ "11a14e7aab4d6374ed195ed549a1279c8a3afd910bc61e7fabf6e8b046a3aa27");
		final AccountKey account = AccountKey.fromHex("e468f1df4a0267c7dc5e0b8869c51820ff1db5204cc7d1aa64cba7f2b79b5e7a");
		final Map<String, String> signatures = Map.of(
			"02ffce729dcedd30046c63f3284feea462650ef2ebc5cff62570ad3aecdf934881",
			"HzPXLYr6vriQLWK92mt3wWdIaO+nfUy8IbAayYaOjIIlXUzGCy9B4WRKObBsAUSgnCLBSRETaZBR9lzlOEzgP7s=",
			"04ffce729dcedd30046c63f3284feea462650ef2ebc5cff62570ad3aecdf934881"
				+ "94a63953ef2268ab06d825b6804bd1e1ac25030450d4ee323cfdfeb921f006d0",
			"GzPXLYr6vriQLWK92mt3wWdIaO+nfUy8IbAayYaOjIIlXUzGCy9B4WRKObBsAUSgnCLBSRETaZBR9lzlOEzgP7s=");

		signatures.forEach((key, base64) -> {
			final byte[] publicKey = HexFormat.of().parseHex(key);
			final byte[] signature = Base64.getDecoder().decode(base64);

			assertThat(LegacySignature.recover(Claim.message(chainId, account), signature).orElseThrow(),
				equalTo(publicKey));
			assertThat(new Claim(publicKey, account, signature).hasValidSignature(chainId), is(true));
		});
	}

	@Example
	public void shouldRefuseAMessageThatNeedsMoreThanOneLengthByte() {
		assertThrows(IllegalArgumentException.class, () -> LegacySignature.messageHash("x".repeat(253)));
	}

	private static byte[] withHeader(byte[] signature, int header) {
		final byte[] copy = signature.clone();

		copy[0] = (byte) header;
		return copy;
	}

	private static BigInteger xWithoutPoint() {
		for (long x = 1;; x++) {
			if (!hasPoint(BigInteger.valueOf(x))) {
				return BigInteger.valueOf(x);
			}
		}
	}

	private static BigInteger smallestRWithAPointBeyondTheOrder() {
		final BigInteger n = LegacySignature.CURVE.getN();

		for (BigInteger r = BigInteger.ONE;; r = r.add(BigInteger.ONE)) {
			if (hasPoint(n.add(r))) {
				return r;
			}
		}
	}

	private static boolean hasPoint(BigInteger x) {
		try {
			LegacySignature.CURVE.getCurve().decodePoint(Bytes.concat(new byte[] { 2 },
				BigIntegers.asUnsignedByteArray(32, x)));
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/* The key that made the signature, in the encoding the header names */
	@Property(tries = 60)
	public void shouldRecoverTheKeyThatSigned(@ForAll @IntRange(min = 1, max = 5000) int secret,
		@ForAll boolean compressed, @ForAll @CharRange(from = ' ', to = '~') @StringLength(max = 252) String text) {

		final LegacyWallet wallet = new LegacyWallet(secret, compressed);
		final byte[] signature = wallet.sign(text);
		final byte[] recovered = LegacySignature.recover(text, signature).orElseThrow();

		assertThat(recovered, equalTo(wallet.publicKey()));
		assertThat(LegacySignature.addressOf(recovered), equalTo(wallet.address()));
		assertThat(LegacySignature.verify(recovered, signature, text), is(true));
	}

	@Property(tries = 60)
	public void shouldRecoverAKeyThatVerifiesForEveryHeaderOfTheSameEncoding(
		@ForAll @IntRange(min = 1, max = 5000) int secret, @ForAll boolean compressed,
		@ForAll @IntRange(min = 0, max = 3) int recoveryId) {

		final byte[] signature = withHeader(new LegacyWallet(secret, compressed).sign(MESSAGE),
			27 + recoveryId + (compressed ? 4 : 0));

		LegacySignature.recover(MESSAGE, signature)
			.ifPresent(key -> assertThat(LegacySignature.verify(key, signature, MESSAGE), is(true)));
	}

	@Property(tries = 60)
	public void shouldRecoverAnotherKeyFromTheSignatureOfAnotherText(@ForAll @IntRange(min = 1, max = 5000) int secret,
		@ForAll boolean compressed, @ForAll @CharRange(from = ' ', to = '~') @StringLength(max = 252) String text,
		@ForAll @CharRange(from = ' ', to = '~') @StringLength(max = 252) String other) {

		Assume.that(!text.equals(other));

		final LegacyWallet wallet = new LegacyWallet(secret, compressed);
		final Optional<LegacyAddress> address = LegacySignature.recover(other, wallet.sign(text))
			.map(LegacySignature::addressOf);

		assertThat(address, not(equalTo(Optional.of(wallet.address()))));
	}

	@Example
	public void shouldRecoverNothingFromWhatIsMalformed() {
		final byte[] signature = withHeader(new LegacyWallet(7, true).sign(MESSAGE), 31);
		final BigInteger n = LegacySignature.CURVE.getN();
		final BigInteger field = LegacySignature.CURVE.getCurve().getField().getCharacteristic();
		final BigInteger s = LegacyWallet.sOf(signature);
		final Map<String, byte[]> malformed = new LinkedHashMap<>();

		malformed.put("64 bytes", Arrays.copyOf(signature, 64));
		malformed.put("66 bytes", Arrays.copyOf(signature, 66));
		malformed.put("header 26", withHeader(signature, 26));
		malformed.put("header 35", withHeader(signature, 35));
		malformed.put("r 0", LegacyWallet.withR(signature, BigInteger.ZERO));
		malformed.put("r n", LegacyWallet.withR(signature, n));
		malformed.put("s 0", LegacyWallet.withS(signature, BigInteger.ZERO));
		malformed.put("s n", LegacyWallet.withS(signature, n));
		malformed.put("high s", LegacyWallet.withS(signature, n.subtract(s)));
		malformed.put("no point for r", LegacyWallet.withR(signature, xWithoutPoint()));
		malformed.put("x beyond 32 bytes", LegacyWallet.withR(withHeader(signature, 33), n.subtract(BigInteger.ONE)));
		malformed.put("x beyond the field", LegacyWallet.withR(withHeader(signature, 33), field.subtract(n)));
		malformed.forEach((name, bad) -> assertThat(name, LegacySignature.recover(MESSAGE, bad),
			equalTo(Optional.empty())));
	}

	/* No wallet key makes such a signature in practice (the nonce point must lie beyond the order, about 2^-128), so
	   it is built the other way round: a point R with x = n + r, a chosen s, and the key Q = r^-1 (sR - eG) that makes
	   the signature valid. That verify holds for Q is checked by the verifier, which is not the code that built Q. */
	@Example
	public void shouldRecoverTheKeyWhenTheNonceOverflowedTheOrder() {
		final BigInteger n = LegacySignature.CURVE.getN();
		final BigInteger r = smallestRWithAPointBeyondTheOrder();
		final BigInteger s = BigInteger.TWO;
		final BigInteger hash = new BigInteger(1, LegacySignature.messageHash(MESSAGE));
		final BigInteger rInverse = r.modInverse(n);

		for (final int recoveryId : new int[] { 2, 3 }) {
			final byte[] nonce = Bytes.concat(new byte[] { (byte) (2 + (recoveryId & 1)) },
				BigIntegers.asUnsignedByteArray(32, n.add(r)));
			final byte[] key = ECAlgorithms.sumOfTwoMultiplies(LegacySignature.CURVE.getG(),
				hash.negate().multiply(rInverse).mod(n), LegacySignature.CURVE.getCurve().decodePoint(nonce),
				s.multiply(rInverse).mod(n)).normalize().getEncoded(true);
			final byte[] signature = LegacyWallet.withS(LegacyWallet.withR(
				withHeader(new LegacyWallet(7, true).sign(MESSAGE), 31 + recoveryId), r), s);

			assertThat("recovery id " + recoveryId, LegacySignature.verify(key, signature, MESSAGE), is(true));
			assertThat("recovery id " + recoveryId, LegacySignature.recover(MESSAGE, signature).orElseThrow(),
				equalTo(key));
		}
	}

	@Example
	public void shouldRecoverNothingForATextThatAsciiCannotCarry() {
		final byte[] signature = new LegacyWallet(7, true).sign(MESSAGE);

		assertThat(LegacySignature.recover("café", signature), equalTo(Optional.empty()));
		assertThat(LegacySignature.recover("x".repeat(253), signature), equalTo(Optional.empty()));
	}
}
