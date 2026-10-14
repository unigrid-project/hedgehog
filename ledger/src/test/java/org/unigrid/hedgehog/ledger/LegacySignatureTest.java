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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import net.jqwik.api.Example;
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

	@Example
	public void shouldRefuseAMessageThatNeedsMoreThanOneLengthByte() {
		assertThrows(IllegalArgumentException.class, () -> LegacySignature.messageHash("x".repeat(253)));
	}
}
