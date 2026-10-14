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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/* An account sends part of its balance to any address, and pays a fee for it. The nonce is the number of
   transfers the sender has made before this one, so each transfer can be applied once and in order. Every
   field is under the signature, and so is the chain, so a transfer cannot be changed or taken to another chain. */
public record Transfer(AccountKey sender, Address recipient, long amount, long fee, long nonce, byte[] signature)
	implements Transaction {
	public static final byte KIND = 4;
	public static final int MAX_ENCODED_SIZE = 1 + AccountKey.SIZE + 1 + AccountKey.SIZE + 3 * Long.BYTES
		+ Ed25519.SIGNATURE_SIZE;
	private static final byte[] TAG = "Unigrid transfer v1".getBytes(StandardCharsets.US_ASCII);

	public Transfer {
		Objects.requireNonNull(sender);
		Objects.requireNonNull(recipient);

		if (amount <= 0) {
			throw new IllegalArgumentException("A transfer is of an amount above 0, found " + amount);
		}

		if (fee < 0 || nonce < 0) {
			throw new IllegalArgumentException("A fee and a nonce are 0 or more, found " + fee + " and "
				+ nonce);
		}

		signature = Bytes.requireSize(signature, Ed25519.SIGNATURE_SIZE, "A transfer signature");
	}

	public static Transfer signed(byte[] senderSeed, Address recipient, long amount, long fee, long nonce,
		byte[] chainId) {
		final AccountKey sender = Ed25519.publicKey(senderSeed);

		return new Transfer(sender, recipient, amount, fee, nonce,
			Ed25519.sign(senderSeed, payload(sender, recipient, amount, fee, nonce, chainId)));
	}

	public boolean hasValidSignature(byte[] chainId) {
		return Ed25519.verify(sender, payload(sender, recipient, amount, fee, nonce, chainId), signature);
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
		return Bytes.concat(new byte[] { KIND }, sender.bytes(), recipient.encode(), Bytes.longBytes(amount),
			Bytes.longBytes(fee), Bytes.longBytes(nonce), signature);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Transfer transfer && sameParties(transfer) && sameAmounts(transfer)
			&& Arrays.equals(signature, transfer.signature);
	}

	private boolean sameParties(Transfer other) {
		return sender.equals(other.sender) && recipient.equals(other.recipient);
	}

	private boolean sameAmounts(Transfer other) {
		return amount == other.amount && fee == other.fee && nonce == other.nonce;
	}

	@Override
	public int hashCode() {
		return 31 * Objects.hash(sender, recipient, amount, fee, nonce) + Arrays.hashCode(signature);
	}

	@Override
	public String toString() {
		return "Transfer[sender=" + sender + ", recipient=" + recipient + ", amount=" + amount + ", fee=" + fee
			+ ", nonce=" + nonce + "]";
	}

	private static byte[] payload(AccountKey sender, Address recipient, long amount, long fee, long nonce,
		byte[] chainId) {
		return Digests.sha512(TAG, chainId, sender.bytes(), recipient.encode(), Bytes.longBytes(amount),
			Bytes.longBytes(fee), Bytes.longBytes(nonce));
	}
}
