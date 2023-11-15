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

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.ECKey.ECDSASignature;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.SignatureDecodeException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;

@Slf4j
public class GridnodeKey {

	public static boolean verifySignature(byte[] message, byte[] signatureBytes, ECKey publicKey)
		throws SignatureDecodeException {

		// Decode the signature bytes
		ECDSASignature signature = ECDSASignature.decodeFromDER(signatureBytes);
		// Hash the message (assuming SHA-256 is used)
		Sha256Hash messageHash = Sha256Hash.of(message);

		return publicKey.verify(messageHash, signature);
	}

	public static boolean verifySignature(byte[] message, byte[] signatureBytes, ECKey[] publicKeys)
		throws SignatureDecodeException {
		boolean verify = false;
		for (ECKey key : publicKeys) {
			// Decode the signature bytes
			ECDSASignature signature = ECDSASignature.decodeFromDER(signatureBytes);

			// Hash the message (assuming SHA-256 is used)
			Sha256Hash messageHash = Sha256Hash.of(message);
			verify = key.verify(messageHash, signature);
		}

		return verify;
	}

	//TODO: Adam do an extra review on this one
	public static List<ECKey> generateKeys(String account, int numKeys) {
		List<ECKey> derivedKeysList = new ArrayList<>();
		try {
			
			byte[] seed = Sha256Hash.hash(account.getBytes());
			//System.out.println("account: " + account);
			//System.out.println("seed: " + seed);

			// Current time in milliseconds since epoch
			long creationTimeSeconds = System.currentTimeMillis() / 1000L;

			// Generate the HD wallet from the seed
			DeterministicSeed deterministicSeed = new DeterministicSeed(seed, "",
				creationTimeSeconds);
			DeterministicKeyChain chain = DeterministicKeyChain.builder()
				.seed(deterministicSeed).build();

			// Derive child keys
			DeterministicKey parentKey = chain.getWatchingKey();
			for (int i = 0; i < numKeys; i++) {
				DeterministicKey childKey = HDKeyDerivation.deriveChildKey(parentKey,
					new ChildNumber(i));
				derivedKeysList.add(ECKey.fromPrivate(childKey.getPrivKey()));
			}

			ECKey[] derivedKeys = derivedKeysList.toArray(new ECKey[0]);

			//cryptoUtils.printKeys(derivedKeys);
			// Now iterate through the allKeys array, signing and verifying a message with
			// each key
			String messageStr = "Start gridnode message";
			byte[] messageBytes = messageStr.getBytes();

			for (int i = 0; i < derivedKeys.length; i++) {
				// Sign the message with the current derived key
				//System.out.println("derivedKeys[i]: " + derivedKeys[i]);
				ECDSASignature signature = derivedKeys[i]
					.sign(Sha256Hash.of(messageBytes));
				byte[] signatureBytes = signature.encodeToDER();

				// Create a single-key array for verification
				ECKey[] singleKeyArray = {
					derivedKeys[i]
				};

				// Verify the signature
				boolean isVerified = verifySignature(messageBytes,
					signatureBytes, singleKeyArray);
				//System.out.println("Verification for key " + i + ": "
				//	+ (isVerified ? "Succeeded" : "Failed"));
			}
		} catch (Exception ex) {
			log.error(ex.getMessage());
		}

		return derivedKeysList;
	}
}
