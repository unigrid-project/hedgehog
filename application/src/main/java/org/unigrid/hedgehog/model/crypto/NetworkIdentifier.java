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

package org.unigrid.hedgehog.model.crypto;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@ApplicationScoped
@Slf4j
public class NetworkIdentifier {

	private static final String KEYPAIR_NAME = "EC";
	private static final String SIGNATURE_NAME = "SHA512WithECDSA";
	private static final String EC_SEC_NAME = "secp521r1";
	/* P‐521 */

	public static final int PRIVATE_KEY_SIZE = 520;
	public static final int PRIVATE_KEY_HEX_SIZE = 65;
	public static final int PUBLIC_KEY_SIZE = 1042;
	public static final int PUBLIC_KEY_HEX_SIZE = 131;

	private ECPrivateKey privateKey;
	private ECPublicKey publicKey;

	@Getter
	@Setter
	private List<String> networkKeys = new ArrayList<>();

	@SneakyThrows
	@PostConstruct
	public void init() {
		final ECGenParameterSpec ec = new ECGenParameterSpec(EC_SEC_NAME);
		final KeyPairGenerator generator = KeyPairGenerator.getInstance(KEYPAIR_NAME);
		generator.initialize(ec, new SecureRandom());

		while (true) {
			generator.initialize(ec, new SecureRandom());

			final KeyPair keypair = generator.generateKeyPair();
			publicKey = (ECPublicKey) keypair.getPublic();
			privateKey = (ECPrivateKey) keypair.getPrivate();

			final int xPubLength = publicKey.getW().getAffineX().bitLength();
			final int yPubLength = publicKey.getW().getAffineY().bitLength();
			final int privLength = privateKey.getS().bitLength();

			/* We require a public key size of 1042 bits and a private key of 520 bits */
			if (xPubLength == PUBLIC_KEY_SIZE / 2 && yPubLength == PUBLIC_KEY_SIZE / 2
				&& privLength == PRIVATE_KEY_SIZE) {
				break;
			}
		}
	}

	public String getPublicKey() {
		return publicKey.getW().getAffineX().toString(16) + publicKey.getW().getAffineY().toString(16);
	}

	public byte[] sign(String s) throws SigningException {
		return sign(Base64.getDecoder().decode(s));
	}

	public byte[] sign(byte[] data) throws SigningException {
		try {
			final java.security.Signature signature = java.security.Signature.getInstance(SIGNATURE_NAME);

			signature.initSign(privateKey);
			signature.update(data);
			return signature.sign();

		} catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException ex) {
			throw new SigningException("Failed to sign data", ex);
		}
	}

	public boolean verifyOther(byte[] data, byte[] signatureData) throws VerifySignatureException {
		try {
			for (String s : networkKeys) {
				byte[] encode = Base64.getDecoder().decode(s);

				KeyFactory kf = KeyFactory.getInstance(EC_SEC_NAME);

				EncodedKeySpec keySpec = new X509EncodedKeySpec(encode);

				ECPublicKey pubKey = (ECPublicKey) kf.generatePublic(keySpec);

				final java.security.Signature signature
					= java.security.Signature.getInstance(SIGNATURE_NAME);

				signature.initVerify(publicKey);
				signature.update(data);
				return signature.verify(signatureData);
			}
			return false;
		} catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException
			| InvalidKeySpecException ex) {
			throw new VerifySignatureException(String.format("Failed to verify siugnature data "
				+ "with public key '%s'", publicKey), ex);
		}
	}

	public boolean verify(byte[] data, byte[] signatureData) throws VerifySignatureException {
		try {
			final java.security.Signature signature = java.security.Signature.getInstance(SIGNATURE_NAME);

			signature.initVerify(publicKey);
			signature.update(data);
			return signature.verify(signatureData);

		} catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException ex) {
			throw new VerifySignatureException(String.format("Failed to verify siugnature data "
				+ "with public key '%s'", publicKey), ex);
		}
	}
}
