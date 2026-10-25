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
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.ObjectUtils;

public class Signature {
	private static final String KEYPAIR_NAME = "EC";
	private static final String SIGNATURE_NAME = "SHA512WithECDSA";
	private static final String DIGEST_SIGNATURE_NAME = "NONEwithECDSA";
	private static final String EC_SEC_NAME = "secp521r1"; /* P‐521 */
	private static final ECParameterSpec CURVE = curve();
	private static final BigInteger HALF_ORDER = CURVE.getOrder().shiftRight(1);

	public static final int PRIVATE_KEY_SIZE = 520;
	public static final int PRIVATE_KEY_HEX_SIZE = 65;
	public static final int PUBLIC_KEY_SIZE = 1042;
	public static final int PUBLIC_KEY_HEX_SIZE = 131;

	private static final int MAX_VERIFIED_KEYS = 64;
	private static final Map<String, ECPublicKey> VERIFIED_KEYS = new ConcurrentHashMap<>();
	private static final Pattern PUBLIC_KEY = Pattern.compile("[0-9a-f]{" + 2 * PUBLIC_KEY_HEX_SIZE + "}");

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

	/**
	* Signs in the one form of a signature ({@link #isInOneForm}), as what is new data is signed: of the two values of
	* s that verify, the lower one is taken.
	*/
	public byte[] signInOneForm(byte[] data) throws SigningException {
		final byte[] signature = sign(data);
		final BigInteger[] rs = SignatureDer.decoded(signature).orElseThrow(
			() -> new SigningException("The provider made a signature that is no DER of two integers")
		);

		return rs[1].compareTo(HALF_ORDER) > 0 ? SignatureDer.encoded(rs[0], CURVE.getOrder().subtract(rs[1]))
			: signature;
	}

	/**
	* Whether a signature is in its one form: DER of the shortest length, of an r and an s within the order of the
	* curve, and of the lower of the two values of s that verify. Whoever holds a signature can write it in another
	* form that verifies the same, a copy of other bytes, so what is new data takes a signature in no other form.
	*/
	public static boolean isInOneForm(byte[] signature) {
		return SignatureDer.decoded(signature).filter(rs -> rs[0].signum() > 0
			&& rs[0].compareTo(CURVE.getOrder()) < 0 && rs[1].signum() > 0 && rs[1].compareTo(HALF_ORDER) <= 0)
			.filter(rs -> Arrays.equals(SignatureDer.encoded(rs[0], rs[1]), signature)).isPresent();
	}

	public boolean verify(byte[] data, byte[] signatureData) throws VerifySignatureException {
		return verify(SIGNATURE_NAME, data, signatureData);
	}

	/* Accepts signatures made with SIGNATURE_NAME, as a SHA-512 digest fits the P-521 order untruncated */
	public boolean verifyDigest(byte[] digest, byte[] signatureData) throws VerifySignatureException {
		return verify(DIGEST_SIGNATURE_NAME, digest, signatureData);
	}

	/* A signature over a digest is one over what was digested, so it is remembered as the same signature */
	private boolean verify(String algorithm, byte[] data, byte[] signatureData) throws VerifySignatureException {
		final byte[] digest = DIGEST_SIGNATURE_NAME.equals(algorithm) ? data : DigestUtils.sha512(data);
		final String key = getPublicKey();

		if (VerifiedSignatures.isKnown(key, digest, signatureData)) {
			return true;
		}

		CheckMeter.charge();

		try {
			final java.security.Signature signature = java.security.Signature.getInstance(algorithm);

			signature.initVerify(publicKey);
			signature.update(data);
			return remembered(key, digest, signatureData, signature.verify(signatureData));

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

	/* As isSignedBy() answers, for a key of the board, whose signatures that verify are remembered */
	public static boolean isSignedByBoardKey(String publicKeyHex, byte[] data, byte[] signatureData) {
		return isSignedBy(publicKeyHex, data, signatureData, true);
	}

	/* Neither the key nor what verified is kept for the next check: the keys of gossiped entries are anyone's to make,
	   and signatures with them that verify, which would fill the caches that hold the few keys the network signs with,
	   and what those signed */
	public static boolean isSignedBy(String publicKeyHex, byte[] data, byte[] signatureData) {
		return isSignedBy(publicKeyHex, data, signatureData, false);
	}

	private static boolean isSignedBy(String publicKeyHex, byte[] data, byte[] signatureData, boolean isRemembered) {
		if (ObjectUtils.anyNull(publicKeyHex, data, signatureData)) {
			return false;
		}

		final byte[] digest = DigestUtils.sha512(data);

		if (VerifiedSignatures.isKnown(publicKeyHex, digest, signatureData)) {
			return true;
		}

		CheckMeter.charge();

		try {
			final java.security.Signature signature = java.security.Signature.getInstance(SIGNATURE_NAME);

			signature.initVerify(publicKeyOf(publicKeyHex));
			signature.update(data);

			final boolean verifies = signature.verify(signatureData);

			return isRemembered ? remembered(publicKeyHex, digest, signatureData, verifies) : verifies;

		} catch (GeneralSecurityException | RuntimeException ex) {
			/* Whatever the provider objects to in a hostile key or signature, the answer is: not signed */
			return false;
		}
	}

	private static boolean remembered(String key, byte[] digest, byte[] signature, boolean verifies) {
		if (verifies) {
			VerifiedSignatures.remember(key, digest, signature);
		}

		return verifies;
	}

	/* A point of the curve, in lower-case hex of the length that getPublicKey() gives, so that a key has one spelling
	   in a list that is compared as text. The provider makes a key of any point it is given, and a point off the
	   curve is no key that anyone holds the private part of. */
	public static boolean isPublicKey(String hex) {
		if (Objects.isNull(hex) || !PUBLIC_KEY.matcher(hex).matches()) {
			return false;
		}

		final BigInteger p = ((ECFieldFp) CURVE.getCurve().getField()).getP();
		final BigInteger x = new BigInteger(hex.substring(0, PUBLIC_KEY_HEX_SIZE), 16);
		final BigInteger y = new BigInteger(hex.substring(PUBLIC_KEY_HEX_SIZE), 16);
		final BigInteger curve = x.pow(3).add(CURVE.getCurve().getA().multiply(x)).add(CURVE.getCurve().getB());

		return x.compareTo(p) < 0 && y.compareTo(p) < 0 && y.pow(2).mod(p).equals(curve.mod(p));
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
