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

package org.unigrid.hedgehog.proxy.session;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Fingerprint;
import org.unigrid.hedgehog.proxy.session.SealException.Reason;

/* AES-256-GCM in both directions of one session, after ChunkCipher. A sealed payload is the u64 counter
   followed by ciphertext and tag. The nonce is direction(1) || 0x000000 || counter(8) and the associated
   data chain id || fingerprint || direction. A per-writer feed delivers a session's entries in order and
   without loss, so the receiver takes exactly the next counter and anything else ends the session. */
public final class SessionCipher {
	public static final int TAG_SIZE = 16;
	public static final int NONCE_SIZE = 12;
	public static final int OVERHEAD = Long.BYTES + TAG_SIZE;

	private final SecretKeySpec sendKey;
	private final SecretKeySpec receiveKey;
	private final Direction sending;
	private final byte[] context;
	private long sendCounter;
	private long receiveCounter;

	public SessionCipher(SessionKeys keys, Direction sending, ChainId chainId, Fingerprint fingerprint) {
		this(keys, sending, chainId, fingerprint, 0, 0);
	}

	SessionCipher(SessionKeys keys, Direction sending, ChainId chainId, Fingerprint fingerprint, long sendCounter,
		long receiveCounter) {
		sendKey = new SecretKeySpec(keys.key(sending), "AES");
		receiveKey = new SecretKeySpec(keys.key(sending.reverse()), "AES");
		this.sending = sending;
		context = ByteBuffer.allocate(ChainId.SIZE + Fingerprint.SIZE).put(chainId.bytes()).put(fingerprint.bytes())
			.array();
		this.sendCounter = sendCounter;
		this.receiveCounter = receiveCounter;
	}

	public synchronized byte[] seal(byte[] plaintext) {
		if (sendCounter == Long.MAX_VALUE) {
			throw new IllegalStateException("The session has sealed all the payloads its counter allows");
		}

		try {
			final Cipher cipher = cipher(Cipher.ENCRYPT_MODE, sendKey, sending, sendCounter);
			final ByteBuffer sealed = ByteBuffer.allocate(OVERHEAD + plaintext.length).putLong(sendCounter++);

			cipher.doFinal(ByteBuffer.wrap(plaintext), sealed);
			return sealed.array();
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("AES-GCM is mandatory on every Java platform", ex);
		}
	}

	public synchronized byte[] open(byte[] sealed) throws SealException {
		if (sealed.length < OVERHEAD) {
			throw new SealException(Reason.MALFORMED, "A sealed payload has at least " + OVERHEAD
				+ " bytes, found " + sealed.length);
		}

		final long counter = ByteBuffer.wrap(sealed).getLong();

		requireNext(counter);

		try {
			final byte[] plaintext = cipher(Cipher.DECRYPT_MODE, receiveKey, sending.reverse(), counter)
				.doFinal(sealed, Long.BYTES, sealed.length - Long.BYTES);

			receiveCounter++;
			return plaintext;
		} catch (AEADBadTagException ex) {
			throw new SealException(Reason.TAMPERED, "Sealed payload " + counter + " fails authentication");
		} catch (GeneralSecurityException ex) {
			throw new IllegalStateException("AES-GCM is mandatory on every Java platform", ex);
		}
	}

	private void requireNext(long counter) throws SealException {
		final int order = Long.compareUnsigned(counter, receiveCounter);

		if (order < 0) {
			throw new SealException(Reason.REPLAYED, "Sealed payload " + counter + " was opened before");
		}

		if (order > 0) {
			throw new SealException(Reason.SKIPPED, "Expected sealed payload " + receiveCounter + ", found "
				+ Long.toUnsignedString(counter));
		}
	}

	private Cipher cipher(int mode, SecretKeySpec key, Direction direction, long counter)
		throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		final byte[] nonce = ByteBuffer.allocate(NONCE_SIZE).put(direction.code())
			.putLong(NONCE_SIZE - Long.BYTES, counter).array();

		cipher.init(mode, key, new GCMParameterSpec(TAG_SIZE * Byte.SIZE, nonce));
		cipher.updateAAD(ByteBuffer.allocate(context.length + 1).put(context).put(direction.code()).array());
		return cipher;
	}
}
