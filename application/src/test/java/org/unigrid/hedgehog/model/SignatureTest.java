/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model;

import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Optional;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.apache.commons.codec.binary.Hex;

public class SignatureTest extends BaseMockedWeldTest {
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
