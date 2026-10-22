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

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
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
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class Signature {
	private static final String KEYPAIR_NAME = "EC";
	private static final String SIGNATURE_NAME = "SHA512WithECDSA";
	private static final String DIGEST_SIGNATURE_NAME = "NONEwithECDSA";
	private static final String EC_SEC_NAME = "secp521r1"; /* P‐521 */
	private static final ECParameterSpec CURVE = curve();

	public static final int PRIVATE_KEY_SIZE = 520;
	public static final int PRIVATE_KEY_HEX_SIZE = 65;
	public static final int PUBLIC_KEY_SIZE = 1042;
	public static final int PUBLIC_KEY_HEX_SIZE = 131;

	private static final int MAX_VERIFIED_KEYS = 64;
	private static final Map<String, ECPublicKey> VERIFIED_KEYS = new ConcurrentHashMap<>();

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

	/* Only the keys given are made. Generating a key pair takes a dozen tries on average to find one of the sizes
	   above, which is no cost to pay for a key that is thrown away, so only the constructor without arguments does. */
	public Signature(Optional<String> privateKeyHex, Optional<String> publicKeyHex)
		throws InvalidAlgorithmParameterException, InvalidKeySpecException, NoSuchAlgorithmException {

		if (privateKeyHex.isPresent()) {
			privateKey = privateKeyOf(privateKeyHex.get());
		}

		if (publicKeyHex.isPresent()) {
			publicKey = publicKeyOf(publicKeyHex.get());
		}
	}

	private Signature(ECPublicKey publicKey) {
		this.publicKey = publicKey;
	}

	public String getPrivateKey() {
		return privateKey.getS().toString(16);
	}

	public String getPublicKey() {
		return publicKey.getW().getAffineX().toString(16) + publicKey.getW().getAffineY().toString(16);
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

	public boolean verify(byte[] data, byte[] signatureData) throws VerifySignatureException {
		return verify(SIGNATURE_NAME, data, signatureData);
	}

	/* Accepts signatures made with SIGNATURE_NAME, as a SHA-512 digest fits the P-521 order untruncated */
	public boolean verifyDigest(byte[] digest, byte[] signatureData) throws VerifySignatureException {
		return verify(DIGEST_SIGNATURE_NAME, digest, signatureData);
	}

	private boolean verify(String algorithm, byte[] data, byte[] signatureData) throws VerifySignatureException {
		try {
			final java.security.Signature signature = java.security.Signature.getInstance(algorithm);

			signature.initVerify(publicKey);
			signature.update(data);
			return signature.verify(signatureData);

		} catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException ex) {
			throw new VerifySignatureException(String.format("Failed to verify siugnature data "
				+ "with public key '%s'", publicKey), ex);
		}
	}

	public static boolean verify(Signable signable, String key) throws VerifySignatureException {
		return verifier(key).verify(signable.getSignable(), signable.getSignature());
	}

	public static boolean verifyDigest(byte[] digest, byte[] signatureData, String key)
		throws VerifySignatureException {

		return verifier(key).verifyDigest(digest, signatureData);
	}

	/* The key is not kept for the next check: the keys of gossiped entries are anyone's to make, and would fill the
	   cache that holds the few keys the network signs with */
	public static boolean isSignedBy(String publicKeyHex, byte[] data, byte[] signatureData) {
		try {
			final java.security.Signature signature = java.security.Signature.getInstance(SIGNATURE_NAME);

			signature.initVerify(publicKeyOf(publicKeyHex));
			signature.update(data);
			return signature.verify(signatureData);

		} catch (GeneralSecurityException | RuntimeException ex) {
			/* Whatever the provider objects to in a hostile key or signature, the answer is: not signed */
			return false;
		}
	}

	private static ECPrivateKey privateKeyOf(String hex) throws InvalidKeySpecException, NoSuchAlgorithmException {
		if (hex.length() / 2 != PRIVATE_KEY_HEX_SIZE) {
			throw new IllegalArgumentException(String.format("Private key is required to be %d bytes, "
				+ "but was %d bytes", PRIVATE_KEY_HEX_SIZE, hex.length() / 2));
		}

		final KeySpec spec = new ECPrivateKeySpec(new BigInteger(hex, 16), CURVE);

		return (ECPrivateKey) KeyFactory.getInstance(KEYPAIR_NAME).generatePrivate(spec);
	}

	private static ECPublicKey publicKeyOf(String hex) throws InvalidKeySpecException, NoSuchAlgorithmException {
		if (hex.length() != PUBLIC_KEY_HEX_SIZE * 2) {
			throw new IllegalArgumentException(String.format("Public key is required to be %d bytes, "
				+ "but was %d bytes", PUBLIC_KEY_HEX_SIZE, hex.length() / 2));
		}

		final int midpoint = hex.length() / 2;
		final ECPoint point = new ECPoint(new BigInteger(hex.substring(0, midpoint), 16),
			new BigInteger(hex.substring(midpoint), 16)
		);

		return (ECPublicKey) KeyFactory.getInstance(KEYPAIR_NAME).generatePublic(new ECPublicKeySpec(point, CURVE));
	}

	/* Anyone can send a spork, and it is checked against every network key, so the point of each key is worked out
	   once. Only the keys checked against are kept, which are the few network keys, and never more than the bound. */
	private static ECPublicKey verifiedKeyOf(String hex) throws InvalidKeySpecException, NoSuchAlgorithmException {
		final ECPublicKey known = VERIFIED_KEYS.get(hex);

		if (Objects.nonNull(known)) {
			return known;
		}

		final ECPublicKey key = publicKeyOf(hex);

		if (VERIFIED_KEYS.size() < MAX_VERIFIED_KEYS) {
			VERIFIED_KEYS.putIfAbsent(hex, key);
		}

		return key;
	}

	private static ECParameterSpec curve() {
		try {
			final AlgorithmParameters parameters = AlgorithmParameters.getInstance(KEYPAIR_NAME);

			parameters.init(new ECGenParameterSpec(EC_SEC_NAME));
			return parameters.getParameterSpec(ECParameterSpec.class);

		} catch (NoSuchAlgorithmException | InvalidParameterSpecException ex) {
			throw new IllegalStateException("The P-521 curve is not available", ex);
		}
	}

	private static Signature verifier(String key) throws VerifySignatureException {
		try {
			return new Signature(verifiedKeyOf(key));

		} catch (InvalidKeySpecException | NoSuchAlgorithmException ex) {
			throw new VerifySignatureException(String.format("Failed to create signature "
				+ "with public key '%s'", key), ex
			);
		}
	}
}
