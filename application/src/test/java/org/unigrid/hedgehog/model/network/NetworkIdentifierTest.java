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

package org.unigrid.hedgehog.model.network;

import jakarta.inject.Inject;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;
import org.unigrid.hedgehog.model.crypto.NetworkIdentifier;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import org.unigrid.hedgehog.model.crypto.SigningException;
import org.unigrid.hedgehog.model.crypto.VerifySignatureException;

public class NetworkIdentifierTest extends BaseMockedWeldTest{
	
	@Inject
	private NetworkIdentifier id;
	
	private void addOtherKey() {
		for (int i = 0; i < 10; i++) {
			try {
				final String KEYPAIR_NAME = "EC";
				final String SIGNATURE_NAME = "SHA512WithECDSA";
				final String EC_SEC_NAME = "secp521r1"; /* P‐521 */
				
				final int PRIVATE_KEY_SIZE = 520;
				final int PRIVATE_KEY_HEX_SIZE = 65;
				final int PUBLIC_KEY_SIZE = 1042;
				final int PUBLIC_KEY_HEX_SIZE = 131;
				
				ECPrivateKey privateKey;
				ECPublicKey publicKey;
				
				final ECGenParameterSpec ec = new ECGenParameterSpec(EC_SEC_NAME);
				final KeyPairGenerator generator = KeyPairGenerator.getInstance(KEYPAIR_NAME);
				generator.initialize(ec, new SecureRandom());
				
				final KeyPair keyPair = generator.generateKeyPair();
				privateKey = (ECPrivateKey) keyPair.getPrivate();
				publicKey = (ECPublicKey) keyPair.getPublic();
				
				id.getNetworkKeys().add(publicKey.getW().getAffineX().toString(16)
					+ publicKey.getW().getAffineY().toString(16));
			} catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException ex) {
				Logger.getLogger(NetworkIdentifierTest.class.getName()).log(Level.SEVERE, null, ex);
			}
		}
	}
	
	@Example
	public void shouldVerifyWithOwnKey() {
		String message = "test";
		boolean success = false;
		try {
			byte[] sign = id.sign(message);
			success = id.verify(Base64.getDecoder().decode(message), sign);
		} catch (SigningException | VerifySignatureException ex) {
			System.out.println(ex.getMessage());
		}
		System.out.println(success);
		assert success == true;
	}

	@Example
	public void shouldVerifyOtherKey() {
		String message = "test";
		boolean success = false;
		try {
			byte[] sign = id.sign(message);
			id.getNetworkKeys().add(id.getPublicKey());
			success = id.verifyOther(Base64.getDecoder().decode(message), sign);
		} catch (SigningException | VerifySignatureException ex) {
			System.out.println(ex.getMessage());
		}
		System.out.println(success);
		assert success == true;
	}
}
