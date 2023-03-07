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

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
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
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Optional;

public class Signature {
	private static final String KEYPAIR_NAME = "EC";
	private static final String SIGNATURE_NAME = "SHA512WithECDSA";
	private static final String EC_SEC_NAME = "secp521r1"; /* P‐521 */

	public static final int PRIVATE_KEY_SIZE = 520;
	public static final int PRIVATE_KEY_HEX_SIZE = 65;
	public static final int PUBLIC_KEY_SIZE = 1042;
	public static final int PUBLIC_KEY_HEX_SIZE = 131;

	private ECPrivateKey privateKey;
	private ECPublicKey publicKey;

	public Signature() throws InvalidAlgorithmParameterException, NoSuchAlgorithmException {
		final ECGenParameterSpec ec = new ECGenParameterSpec(EC_SEC_NAME);
		final KeyPairGenerator generator = KeyPairGenerator.getInstance(KEYPAIR_NAME);

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

	public Signature(Optional<String> privateKeyHex, Optional<String> publicKeyHex)
		throws InvalidAlgorithmParameterException, InvalidKeySpecException, NoSuchAlgorithmException {
		this();

		final KeyFactory factory = KeyFactory.getInstance(KEYPAIR_NAME);
		final ECParameterSpec params = privateKey.getParams();

		if (privateKeyHex.isPresent()) {
			if (privateKeyHex.get().length() / 2 != PRIVATE_KEY_HEX_SIZE) {
				throw new IllegalArgumentException(
					String.format("Private key is required to be %d bytes, but was %d bytes",
					PRIVATE_KEY_HEX_SIZE, privateKeyHex.get().length() / 2)
				);
			}

			final KeySpec privSpec = new ECPrivateKeySpec(new BigInteger(privateKeyHex.get(), 16), params);
			privateKey = (ECPrivateKey) factory.generatePrivate(privSpec);
		}

		if (publicKeyHex.isPresent()) {
			final int midpoint = publicKeyHex.get().length() / 2;
			final BigInteger x = new BigInteger(publicKeyHex.get().substring(0, midpoint), 16);
			final BigInteger y = new BigInteger(publicKeyHex.get().substring(midpoint), 16);
			final KeySpec pubSpec = new ECPublicKeySpec(new ECPoint(x, y), params);

			publicKey = (ECPublicKey) factory.generatePublic(pubSpec);

			final int xPubLength = publicKey.getW().getAffineX().bitLength();
			final int yPubLength = publicKey.getW().getAffineY().bitLength();

			if (publicKeyHex.get().length() / 2 != PUBLIC_KEY_HEX_SIZE) {
				throw new IllegalArgumentException(
					String.format("Public key is required to be %d bytes, but was %d bytes",
					PUBLIC_KEY_HEX_SIZE, publicKeyHex.get().length() / 2)
				);
			}
		}
	}

	public String getPrivateKey() {
		return privateKey.getS().toString(16);
	}

	public String getPublicKey() {
		return publicKey.getW().getAffineX().toString(16) + publicKey.getW().getAffineY().toString(16);
	}

	public byte[] sign(byte[] data) throws InvalidKeyException, NoSuchAlgorithmException, SignatureException {
		final java.security.Signature signature = java.security.Signature.getInstance(SIGNATURE_NAME);

		signature.initSign(privateKey);
		signature.update(data);
		return signature.sign();
	}

	public boolean verify(byte[] data, byte[] signatureData) throws InvalidKeyException, InvalidKeySpecException,
		NoSuchAlgorithmException, SignatureException {

		final java.security.Signature signature = java.security.Signature.getInstance(SIGNATURE_NAME);

		signature.initVerify(publicKey);
		signature.update(data);
		return signature.verify(signatureData);
	}

	public static boolean verify(Signable signable, String key) throws InvalidAlgorithmParameterException,
		InvalidKeySpecException, InvalidKeyException, NoSuchAlgorithmException, SignatureException {

		final Signature signature = new Signature(Optional.empty(), Optional.of(key));
		return signature.verify(signable.getSignable(), signable.getSignature());
	}
}
