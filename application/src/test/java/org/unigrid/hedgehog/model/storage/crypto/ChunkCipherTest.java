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

package org.unigrid.hedgehog.model.storage.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HexFormat;
import lombok.SneakyThrows;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprints.Secrets;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ChunkCipherTest {
	private static FingerprintKeys keys(byte[] secret) {
		return new FingerprintKeys(Fingerprints.of(secret));
	}

	@SneakyThrows
	@Property(tries = 100)
	public void roundTripsChunks(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Size(max = 2048) byte[] plaintext, @ForAll long sequence) {

		final ChunkCipher cipher = ChunkCipher.forChunks(keys(secret));
		final byte[] sealed = cipher.seal(sequence, plaintext);

		assertThat(sealed.length, equalTo(plaintext.length + ChunkCipher.TAG_SIZE));
		assertThat(cipher.open(sequence, sealed), equalTo(plaintext));
	}

	@SneakyThrows
	@Property(tries = 100)
	public void roundTripsManifests(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Size(max = 2048) byte[] plaintext, @ForAll @IntRange(max = FingerprintKeys.MAX_POSITION) int copy) {

		final ChunkCipher cipher = ChunkCipher.forManifest(keys(secret));
		final byte[] sealed = cipher.seal(copy, plaintext);

		assertThat(sealed.length, equalTo(plaintext.length + ChunkCipher.TAG_SIZE));
		assertThat(cipher.open(copy, sealed), equalTo(plaintext));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void rejectsAMovedChunk(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Size(max = 256) byte[] plaintext, @ForAll long sequence, @ForAll long otherSequence) {

		Assume.that(sequence != otherSequence);

		final ChunkCipher cipher = ChunkCipher.forChunks(keys(secret));
		final byte[] sealed = cipher.seal(sequence, plaintext);

		assertThrows(GeneralSecurityException.class, () -> cipher.open(otherSequence, sealed));
	}

	@SneakyThrows
	@Property(tries = 100)
	public void rejectsTamperingAnywhere(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Size(max = 256) byte[] plaintext, @ForAll @IntRange(max = 1024) int position,
		@ForAll @IntRange(min = 1, max = 255) int flip) {

		final ChunkCipher cipher = ChunkCipher.forChunks(keys(secret));
		final byte[] sealed = cipher.seal(0, plaintext);
		sealed[position % sealed.length] ^= (byte) flip;

		assertThrows(GeneralSecurityException.class, () -> cipher.open(0, sealed));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void rejectsAnotherFingerprint(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll(supplier = Secrets.class) byte[] otherSecret, @ForAll @Size(max = 256) byte[] plaintext,
		@ForAll long sequence) {

		Assume.that(!Arrays.equals(secret, otherSecret));

		final byte[] sealed = ChunkCipher.forChunks(keys(secret)).seal(sequence, plaintext);
		final ChunkCipher otherCipher = ChunkCipher.forChunks(keys(otherSecret));

		assertThrows(GeneralSecurityException.class, () -> otherCipher.open(sequence, sealed));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void separatesChunksFromManifests(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Size(max = 64) byte[] plaintext, @ForAll long sequence) {

		final FingerprintKeys keys = keys(secret);
		final byte[] sealed = ChunkCipher.forChunks(keys).seal(sequence, plaintext);

		assertThrows(GeneralSecurityException.class, () -> ChunkCipher.forManifest(keys).open(sequence, sealed));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void separatesManifestsFromChunks(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Size(max = 64) byte[] plaintext, @ForAll @IntRange(max = FingerprintKeys.MAX_POSITION) int copy) {

		final FingerprintKeys keys = keys(secret);
		final byte[] sealed = ChunkCipher.forManifest(keys).seal(copy, plaintext);

		assertThrows(GeneralSecurityException.class, () -> ChunkCipher.forChunks(keys).open(copy, sealed));
	}

	@SneakyThrows
	@Example
	public void sealsTheKnownAnswer() {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys(Fingerprints.knownSecret()));
		final byte[] sealed = cipher.seal(5, "hedgehog".getBytes(StandardCharsets.US_ASCII));

		assertThat(HexFormat.of().formatHex(sealed), equalTo("2d568721669af5c6cfa5ca445778bb9fb9ceebc07f45d41b"));
	}
}
