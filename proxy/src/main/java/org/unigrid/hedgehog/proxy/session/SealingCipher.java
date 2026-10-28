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
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.unigrid.hedgehog.proxy.session.SealException.Reason;

/* AES-256-GCM over the messages of one direction of a session, with the key of that direction: the sending end
   seals with a Sealer and the receiving end opens with an Opener of the same key, direction and binding, so no
   instance can seal and open under one key and counter. A plaintext is framed as u32 length || bytes || zeros
   and sealed to exactly the size the caller asks for, so the size tells nothing but the class. No counter travels
   with a message: the nonce is direction(1) || 0x000000 || counter(8) with the next counter of each end, and the
   associated data is the handshake hash the session was bound to. A per-writer feed delivers a session's entries
   in order and without loss, so a skipped or replayed message fails authentication like a forged one. */
public final class SealingCipher {
	private static final int TAG_SIZE = 16;
	public static final int OVERHEAD = TAG_SIZE + Integer.BYTES;
	private static final int KEY_SIZE = 32;
	private static final int NONCE_SIZE = 12;

	private final SecretKeySpec key;
	private final Direction direction;
	private final byte[] binding;

	private SealingCipher(byte[] key, Direction direction, byte[] binding) {
		if (key.length != KEY_SIZE) {
			throw new IllegalArgumentException("An AES-256 key is exactly " + KEY_SIZE + " bytes, found "
				+ key.length);
		}

		this.key = new SecretKeySpec(key, "AES");
		this.direction = Objects.requireNonNull(direction, "direction");
		this.binding = binding.clone();
	}

	public static Sealer sealer(byte[] key, Direction direction, byte[] binding) {
		return sealer(key, direction, binding, 0);
	}

	static Sealer sealer(byte[] key, Direction direction, byte[] binding, long counter) {
		return new Sealer(new SealingCipher(key, direction, binding), counter);
	}

	public static Opener opener(byte[] key, Direction direction, byte[] binding) {
		return opener(key, direction, binding, 0);
	}

	static Opener opener(byte[] key, Direction direction, byte[] binding, long counter) {
		return new Opener(new SealingCipher(key, direction, binding), counter);
	}

	/* The sending end of one direction */
	public static final class Sealer {
		private final SealingCipher cipher;
		private long counter;

		private Sealer(SealingCipher cipher, long counter) {
			this.cipher = cipher;
			this.counter = counter;
		}

		/* The size is checked against the overhead first, as subtracting the overhead from a size far below it
		   would wrap around */
		public synchronized byte[] seal(byte[] plaintext, int sealedSize) {
			if (sealedSize < OVERHEAD || plaintext.length > sealedSize - OVERHEAD) {
				throw new IllegalArgumentException("A plaintext of " + plaintext.length
					+ " bytes does not fit in " + sealedSize + " sealed bytes");
			}

			if (counter == Long.MAX_VALUE) {
				throw new IllegalStateException("The session has sealed all the messages its counter "
					+ "allows");
			}

			final byte[] framed = ByteBuffer.allocate(sealedSize - TAG_SIZE).putInt(plaintext.length)
				.put(plaintext).array();

			try {
				final byte[] sealed = cipher.cipher(Cipher.ENCRYPT_MODE, counter).doFinal(framed);

				counter++;
				return sealed;
			} catch (GeneralSecurityException ex) {
				throw new IllegalStateException("AES-GCM is mandatory on every Java platform", ex);
			}
		}
	}

	/* The receiving end of one direction */
	public static final class Opener {
		private final SealingCipher cipher;
		private long counter;

		private Opener(SealingCipher cipher, long counter) {
			this.cipher = cipher;
			this.counter = counter;
		}

		public synchronized byte[] open(byte[] sealed) throws SealException {
			if (sealed.length < OVERHEAD) {
				throw new SealException(Reason.MALFORMED, "A sealed message has at least " + OVERHEAD
					+ " bytes, found " + sealed.length);
			}

			final byte[] framed;

			try {
				framed = cipher.cipher(Cipher.DECRYPT_MODE, counter).doFinal(sealed);
			} catch (AEADBadTagException ex) {
				throw new SealException(Reason.TAMPERED, "Sealed message " + counter
					+ " fails authentication");
			} catch (GeneralSecurityException ex) {
				throw new IllegalStateException("AES-GCM is mandatory on every Java platform", ex);
			}

			counter++;

			final long length = Integer.toUnsignedLong(ByteBuffer.wrap(framed).getInt());

			if (length > framed.length - Integer.BYTES) {
				throw new SealException(Reason.MALFORMED, "Sealed message " + (counter - 1) + " claims "
					+ length + " bytes in " + (framed.length - Integer.BYTES));
			}

			return Arrays.copyOfRange(framed, Integer.BYTES, Integer.BYTES + (int) length);
		}
	}

	private Cipher cipher(int mode, long counter) throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		final byte[] nonce = ByteBuffer.allocate(NONCE_SIZE).put(direction.code())
			.putLong(NONCE_SIZE - Long.BYTES, counter).array();

		cipher.init(mode, key, new GCMParameterSpec(TAG_SIZE * Byte.SIZE, nonce));
		cipher.updateAAD(binding);
		return cipher;
	}
}
