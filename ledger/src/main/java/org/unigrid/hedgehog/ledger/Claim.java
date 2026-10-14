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

import java.util.Arrays;
import java.util.Objects;

/* The owner of a legacy address moves what it holds to an account of the ledger. The key is the one that hashes
   to the address and the signature is the one the legacy wallet makes with signmessage over a text that names
   this chain and the recipient, so it cannot be redirected to another recipient or replayed on another chain.
   What the address holds is not in the transaction but in the state, and it can be claimed only once. */
public record Claim(byte[] publicKey, AccountKey recipient, byte[] signature) implements Transaction {
	public static final byte KIND = 3;
	public static final int MAX_ENCODED_SIZE = 1 + LegacySignature.UNCOMPRESSED_KEY_SIZE + AccountKey.SIZE
		+ LegacySignature.SIZE;

	public Claim {
		Objects.requireNonNull(recipient);
		publicKey = Bytes.requireSize(publicKey, keySize(publicKey.length > 0 ? publicKey[0] : 0), "A public key");
		signature = Bytes.requireSize(signature, LegacySignature.SIZE, "A claim signature");
	}

	/* The first byte of an encoded secp256k1 key fixes its width */
	static int keySize(byte prefix) {
		return switch (prefix) {
			case 2, 3 -> LegacySignature.COMPRESSED_KEY_SIZE;
			case 4 -> LegacySignature.UNCOMPRESSED_KEY_SIZE;
			default -> throw new IllegalArgumentException(
				"Unknown public key prefix " + Byte.toUnsignedInt(prefix));
		};
	}

	/* What the owner signs with the wallet: signmessage "<address>" "<this text>" */
	public static String message(byte[] chainId, AccountKey recipient) {
		return "claim " + Bytes.hex(chainId) + " " + Bytes.hex(recipient.bytes());
	}

	public LegacyAddress address() {
		return LegacySignature.addressOf(publicKey);
	}

	public boolean hasValidSignature(byte[] chainId) {
		return LegacySignature.verify(publicKey, signature, message(chainId, recipient));
	}

	@Override
	public byte[] publicKey() {
		return publicKey.clone();
	}

	@Override
	public byte[] signature() {
		return signature.clone();
	}

	@Override
	public byte kind() {
		return KIND;
	}

	@Override
	public byte[] encode() {
		return Bytes.concat(new byte[] { KIND }, publicKey, recipient.bytes(), signature);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Claim claim && recipient.equals(claim.recipient)
			&& Arrays.equals(publicKey, claim.publicKey) && Arrays.equals(signature, claim.signature);
	}

	@Override
	public int hashCode() {
		return Objects.hash(recipient, Arrays.hashCode(publicKey), Arrays.hashCode(signature));
	}
}
