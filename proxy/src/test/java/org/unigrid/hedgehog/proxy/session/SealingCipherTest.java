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
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.proxy.Expect;
import org.unigrid.hedgehog.proxy.session.SealException.Reason;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class SealingCipherTest {
	private static final byte[] KEY = filled(32, 1);
	private static final byte[] BINDING = filled(32, 2);
	private static final int SMALLEST_CLASS = 1024;
	private static final int TAG_SIZE = 16;

	private static byte[] filled(int size, int value) {
		final byte[] bytes = new byte[size];

		Arrays.fill(bytes, (byte) value);
		return bytes;
	}

	private static SealingCipher cipher() {
		return new SealingCipher(KEY, Direction.CLIENT_TO_EXIT, BINDING);
	}

	private static Reason refusal(SealingCipher cipher, byte[] sealed) {
		return Expect.assertThrows(SealException.class, () -> cipher.open(sealed)).reason();
	}

	/* The documented nonce, laid out byte by byte apart from the code under test */
	private static byte[] documentedNonce(Direction direction, long counter) {
		final byte[] nonce = new byte[12];

		nonce[0] = direction.code();

		for (int i = 0; i < Long.BYTES; i++) {
			nonce[nonce.length - 1 - i] = (byte) (counter >>> (Byte.SIZE * i));
		}

		return nonce;
	}

	private static Cipher documented(int mode, Direction direction, long counter) throws GeneralSecurityException {
		final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

		cipher.init(mode, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(TAG_SIZE * Byte.SIZE,
			documentedNonce(direction, counter)));
		cipher.updateAAD(BINDING);
		return cipher;
	}

	@Property(tries = 100)
	public void sealsToExactlyTheRequestedSize(@ForAll @Size(max = 300) byte[] plaintext,
		@ForAll @IntRange(min = 0, max = 2000) int padding) throws SealException {
		final int sealedSize = plaintext.length + SealingCipher.OVERHEAD + padding;
		final byte[] sealed = cipher().seal(plaintext, sealedSize);

		assertThat(sealed.length, is(sealedSize));
		assertThat(cipher().open(sealed), equalTo(plaintext));
	}

	@Example
	public void refusesAPlaintextLargerThanTheSize() {
		Expect.assertThrows(IllegalArgumentException.class,
			() -> cipher().seal(new byte[10], 10 + SealingCipher.OVERHEAD - 1));
	}

	@Example
	public void refusesASkippedOrReplayedMessage() throws SealException {
		final SealingCipher sender = cipher();
		final SealingCipher receiver = cipher();
		final byte[] first = sender.seal(new byte[] { 1 }, SMALLEST_CLASS);
		final byte[] second = sender.seal(new byte[] { 2 }, SMALLEST_CLASS);
		final byte[] third = sender.seal(new byte[] { 3 }, SMALLEST_CLASS);

		assertThat(receiver.open(first), equalTo(new byte[] { 1 }));
		assertThat(refusal(receiver, first), is(Reason.TAMPERED));
		assertThat(refusal(receiver, third), is(Reason.TAMPERED));

		/* A refused message leaves the counter where it was */
		assertThat(receiver.open(second), equalTo(new byte[] { 2 }));
		assertThat(receiver.open(third), equalTo(new byte[] { 3 }));
	}

	@Example
	public void paddingIsNotReadAsData() throws Exception {
		final byte[] plaintext = { 7, 0, 0 };
		final byte[] sealed = cipher().seal(plaintext, SMALLEST_CLASS);
		final byte[] framed = documented(Cipher.DECRYPT_MODE, Direction.CLIENT_TO_EXIT, 0).doFinal(sealed);
		final byte[] expected = ByteBuffer.allocate(SMALLEST_CLASS - TAG_SIZE).putInt(plaintext.length)
			.put(plaintext).array();

		assertThat(framed, equalTo(expected));
		assertThat(cipher().open(sealed), equalTo(plaintext));
	}

	@Example
	public void opensOnlyTheKeyDirectionAndBindingItWasSealedWith() {
		final byte[] sealed = cipher().seal(new byte[] { 1, 2, 3 }, SMALLEST_CLASS);

		assertThat(refusal(new SealingCipher(filled(32, 3), Direction.CLIENT_TO_EXIT, BINDING), sealed),
			is(Reason.TAMPERED));
		assertThat(refusal(new SealingCipher(KEY, Direction.EXIT_TO_CLIENT, BINDING), sealed), is(Reason.TAMPERED));
		assertThat(refusal(new SealingCipher(KEY, Direction.CLIENT_TO_EXIT, filled(32, 4)), sealed),
			is(Reason.TAMPERED));
	}

	@Property(tries = 100)
	public void refusesEveryFlippedBit(@ForAll @IntRange(min = 0, max = SMALLEST_CLASS - 1) int index,
		@ForAll @IntRange(min = 0, max = 7) int bit) {
		final byte[] sealed = cipher().seal(new byte[] { 1, 2, 3 }, SMALLEST_CLASS);

		sealed[index] ^= (byte) (1 << bit);
		assertThat(refusal(cipher(), sealed), is(Reason.TAMPERED));
	}

	@Example
	public void refusesAMessageTooShortForALengthAndTag() {
		assertThat(refusal(cipher(), new byte[SealingCipher.OVERHEAD - 1]), is(Reason.MALFORMED));
	}

	@Example
	public void refusesALengthPastTheEnd() throws GeneralSecurityException {
		final int framedSize = SMALLEST_CLASS - TAG_SIZE;
		final byte[] framed = ByteBuffer.allocate(framedSize).putInt(framedSize - Integer.BYTES + 1).array();
		final byte[] sealed = documented(Cipher.ENCRYPT_MODE, Direction.CLIENT_TO_EXIT, 0).doFinal(framed);

		assertThat(refusal(cipher(), sealed), is(Reason.MALFORMED));
	}

	@Example
	public void opensWhatThePlatformCipherSealedAsDocumented() throws Exception {
		final byte[] framed = ByteBuffer.allocate(SMALLEST_CLASS - TAG_SIZE).putInt(2).put(new byte[] { 4, 2 })
			.array();
		final SealingCipher receiver = new SealingCipher(KEY, Direction.CLIENT_TO_EXIT, BINDING, 0, 1L << 32);
		final byte[] sealed = documented(Cipher.ENCRYPT_MODE, Direction.CLIENT_TO_EXIT, 1L << 32).doFinal(framed);

		assertThat(receiver.open(sealed), equalTo(new byte[] { 4, 2 }));
	}

	@Example
	public void stopsBeforeTheCounterWrapsAround() throws SealException {
		final SealingCipher sender = new SealingCipher(KEY, Direction.CLIENT_TO_EXIT, BINDING, Long.MAX_VALUE - 1,
			0);
		final SealingCipher receiver = new SealingCipher(KEY, Direction.CLIENT_TO_EXIT, BINDING, 0,
			Long.MAX_VALUE - 1);

		assertThat(receiver.open(sender.seal(new byte[] { 1 }, SMALLEST_CLASS)), equalTo(new byte[] { 1 }));
		Expect.assertThrows(IllegalStateException.class, () -> sender.seal(new byte[] { 2 }, SMALLEST_CLASS));
	}
}
