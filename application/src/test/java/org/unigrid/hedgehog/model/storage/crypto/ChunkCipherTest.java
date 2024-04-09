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

package org.unigrid.hedgehog.model.storage.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import lombok.SneakyThrows;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ChunkCipherTest {
	private static FingerprintKeys keys() {
		return new FingerprintKeys(Fingerprint.generate(new SecureRandom()));
	}

	@SneakyThrows
	@Property(tries = 100)
	public void roundTrips(@ForAll @Size(max = 2048) byte[] plaintext, @ForAll long sequence) {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys());
		final byte[] sealed = cipher.seal(sequence, plaintext);

		assertThat(sealed.length, equalTo(plaintext.length + ChunkCipher.TAG_SIZE));
		assertThat(cipher.open(sequence, sealed), equalTo(plaintext));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void rejectsAMovedChunk(@ForAll @Size(max = 256) byte[] plaintext, @ForAll long sequence) {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys());
		final byte[] sealed = cipher.seal(sequence, plaintext);

		assertThrows(GeneralSecurityException.class, () -> cipher.open(sequence + 1, sealed));
	}

	@SneakyThrows
	@Property(tries = 50)
	public void rejectsTampering(@ForAll @Size(min = 1, max = 256) byte[] plaintext, @ForAll long sequence) {
		final ChunkCipher cipher = ChunkCipher.forChunks(keys());
		final byte[] sealed = cipher.seal(sequence, plaintext);
		sealed[0]++;

		assertThrows(GeneralSecurityException.class, () -> cipher.open(sequence, sealed));
	}

	@SneakyThrows
	@Property(tries = 20)
	public void separatesChunksFromManifests(@ForAll @Size(max = 64) byte[] plaintext) {
		final FingerprintKeys keys = keys();
		final byte[] sealed = ChunkCipher.forChunks(keys).seal(0, plaintext);

		assertThrows(GeneralSecurityException.class, () -> ChunkCipher.forManifest(keys).open(0, sealed));
	}
}
