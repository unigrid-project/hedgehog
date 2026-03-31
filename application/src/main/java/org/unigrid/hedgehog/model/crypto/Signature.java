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

	import java.math.BigInteger;
	import java.security.*;
	import java.security.interfaces.ECPrivateKey;
	import java.security.interfaces.ECPublicKey;
	import java.security.spec.*;
	import java.util.Optional;
	
	/**
	 * Hanterar ECDSA-signering (P-521) och verifiering.
	 */
	public class Signature {
	
		private static final String KEYPAIR_NAME = "EC";
		private static final String SIGNATURE_NAME = "SHA512withECDSA";
		private static final String EC_SEC_NAME = "secp521r1";
	
		private ECPrivateKey privateKey;
		private ECPublicKey publicKey;
	
		public static final int PRIVATE_KEY_HEX_SIZE = 65;  // bytes
		public static final int PUBLIC_KEY_HEX_SIZE = 131;  // bytes
	
		// Genererar ny nyckelpar
		public Signature() throws NoSuchAlgorithmException, InvalidAlgorithmParameterException {
			KeyPairGenerator generator = KeyPairGenerator.getInstance(KEYPAIR_NAME);
			ECGenParameterSpec ecSpec = new ECGenParameterSpec(EC_SEC_NAME);
			generator.initialize(ecSpec, new SecureRandom());
	
			KeyPair keyPair = generator.generateKeyPair();
			this.privateKey = (ECPrivateKey) keyPair.getPrivate();
			this.publicKey = (ECPublicKey) keyPair.getPublic();
		}
	
		// Skapar nycklar från hex-strängar
		public Signature(Optional<String> privateKeyHex, Optional<String> publicKeyHex)
				throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeySpecException {
			this(); // genererar default
	
			KeyFactory factory = KeyFactory.getInstance(KEYPAIR_NAME);
			ECParameterSpec params = privateKey.getParams();
	
			if (privateKeyHex.isPresent()) {
				BigInteger s = new BigInteger(privateKeyHex.get(), 16);
				ECPrivateKeySpec privSpec = new ECPrivateKeySpec(s, params);
				privateKey = (ECPrivateKey) factory.generatePrivate(privSpec);
			}
	
			if (publicKeyHex.isPresent()) {
				String hex = publicKeyHex.get();
				int mid = hex.length() / 2;
				BigInteger x = new BigInteger(hex.substring(0, mid), 16);
				BigInteger y = new BigInteger(hex.substring(mid), 16);
				ECPublicKeySpec pubSpec = new ECPublicKeySpec(new ECPoint(x, y), params);
				publicKey = (ECPublicKey) factory.generatePublic(pubSpec);
			}
		}
	
		public String getPrivateKey() {
			return privateKey.getS().toString(16);
		}
	
		public String getPublicKey() {
			return publicKey.getW().getAffineX().toString(16) + publicKey.getW().getAffineY().toString(16);
		}
	
		public byte[] sign(byte[] data) throws SigningException {
			try {
				java.security.Signature sig = java.security.Signature.getInstance(SIGNATURE_NAME);
				sig.initSign(privateKey);
				sig.update(data);
				return sig.sign();
			} catch (NoSuchAlgorithmException | InvalidKeyException | java.security.SignatureException ex) {
				throw new SigningException("Failed to sign data", ex);
			}
		}
	
		public boolean verify(byte[] data, byte[] signatureData) throws VerifySignatureException {
			try {
				java.security.Signature sig = java.security.Signature.getInstance(SIGNATURE_NAME);
				sig.initVerify(publicKey);
				sig.update(data);
				return sig.verify(signatureData);
			} catch (NoSuchAlgorithmException | InvalidKeyException | java.security.SignatureException ex) {
				throw new VerifySignatureException("Failed to verify signature", ex);
			}
		}
	
		public static boolean verify(Signable signable, String publicKeyHex) throws VerifySignatureException {
			try {
				Signature sig = new Signature(Optional.empty(), Optional.of(publicKeyHex));
				return sig.verify(signable.getSignable(), signable.getSignature());
			} catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | InvalidKeySpecException ex) {
				throw new VerifySignatureException("Failed to create signature for verification", ex);
			}
		}
	}
	