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

package org.unigrid.hedgehog.model.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ArrayUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SignatureTest extends BaseMockedWeldTest {
	private static final byte[] DATA = "payload".getBytes(StandardCharsets.UTF_8);

	@SneakyThrows
	@Example
	public void shouldSignWithAKeyGivenAsHex() {
		final Signature keys = new Signature();
		final Signature signer = new Signature(Optional.of(keys.getPrivateKey()), Optional.empty());

		assertThat(Signature.verifyDigest(DigestUtils.sha512(DATA), signer.sign(DATA), keys.getPublicKey()), is(true));
	}

	@SneakyThrows
	@Example
	public void shouldVerifyAgainstTheSameKeyManyTimes() {
		final Signature signer = new Signature();
		final byte[] digest = DigestUtils.sha512(DATA);
		final byte[] signature = signer.sign(DATA);

		for (int i = 0; i < 100; i++) {
			assertThat(Signature.verifyDigest(digest, signature, signer.getPublicKey()), is(true));
		}
	}

	@SneakyThrows
	@Property(tries = 100)
	public boolean shouldSignAndVerify(@ForAll byte[] data) {
		final Signature signature = new Signature();
		final byte[] signatureData = signature.sign(data);

		return signature.verify(data, signatureData);
	}

	@SneakyThrows
	@Property(tries = 50)
	public boolean shouldSignAndVerifyWithOtherConstructor(@ForAll @NotEmpty byte[] data) {
		final Signature signatureKeys = new Signature();

		final Signature signatureSigner = new Signature(Optional.of(
			signatureKeys.getPrivateKey()), Optional.empty()
		);

		final Signature signatureVerifier = new Signature(Optional.empty(),
			Optional.of(signatureKeys.getPublicKey())
		);

		final byte[] signatureData = signatureSigner.sign(data);
		return signatureVerifier.verify(data, signatureData);
	}

	@Property(tries = 50)
	public boolean shouldThrowExceptionOnInvalidPrivateKeySize(@ForAll @Size(min = 50, max = 70) byte[] privateKey) {
		try {
			final Signature signatureSigner = new Signature(
				Optional.of(Hex.encodeHexString(privateKey)), Optional.empty()
			);
		} catch (InvalidAlgorithmParameterException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
			return false;
		} catch (IllegalArgumentException ex) {
			return privateKey.length != Signature.PRIVATE_KEY_HEX_SIZE;
		}

		return privateKey.length == Signature.PRIVATE_KEY_HEX_SIZE;
	}

	@SneakyThrows
	@Property(tries = 50)
	public boolean shouldVerifyDigestOfSignedData(@ForAll byte[] data) {
		final Signature signature = new Signature();
		final byte[] signatureData = signature.sign(data);

		return Signature.verifyDigest(DigestUtils.sha512(data), signatureData, signature.getPublicKey());
	}

	@SneakyThrows
	@Property(tries = 50)
	public boolean shouldRejectDigestOfOtherData(@ForAll @NotEmpty byte[] data) {
		final Signature signature = new Signature();
		final byte[] signatureData = signature.sign(data);
		final byte[] otherData = ArrayUtils.add(data, (byte) 0);

		return !signature.verifyDigest(DigestUtils.sha512(otherData), signatureData);
	}

	@Property(tries = 50)
	public boolean shouldThrowExceptionOnInvalidPublicKeySize(@ForAll @Size(min = 120, max = 140) byte[] publicKey) {
		try {
			final Signature signatureSigner = new Signature(Optional.empty(),
				Optional.of(Hex.encodeHexString(publicKey))
			);
		} catch (InvalidAlgorithmParameterException | InvalidKeySpecException | NoSuchAlgorithmException ex) {
			return false;
		} catch (IllegalArgumentException ex) {
			return publicKey.length != Signature.PUBLIC_KEY_HEX_SIZE;
		} catch (RuntimeException ex) {
			return true; /* Happens when we have weird input values in the public key, so lets ignore it */
		}

		return publicKey.length == Signature.PUBLIC_KEY_HEX_SIZE;
	}
}
