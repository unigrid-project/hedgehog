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

import java.security.SecureRandom;
import java.util.Arrays;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.bitcoinj.core.Base58;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class FingerprintTest {
	private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

	@Property(tries = 50)
	public void roundTripsThroughText() {
		final Fingerprint fingerprint = Fingerprint.generate(new SecureRandom());

		assertThat(Fingerprint.parse(fingerprint.encode()), equalTo(fingerprint));
	}

	@Property(tries = 50)
	public void parsesWithSurroundingWhitespace() {
		final Fingerprint fingerprint = Fingerprint.generate(new SecureRandom());

		assertThat(Fingerprint.parse("  " + fingerprint.encode() + "\n"), equalTo(fingerprint));
	}

	@Property(tries = 200)
	public void rejectsSingleCharacterTypos(@ForAll @IntRange(min = 0, max = 40) int position,
		@ForAll @IntRange(min = 1, max = 57) int shift) {

		final String encoded = Fingerprint.generate(new SecureRandom()).encode();
		final int index = position % encoded.length();
		final char replacement = ALPHABET.charAt((ALPHABET.indexOf(encoded.charAt(index)) + shift) % ALPHABET.length());
		final String typo = encoded.substring(0, index) + replacement + encoded.substring(index + 1);

		assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(typo));
	}

	@Property(tries = 100)
	public void rejectsUnknownFormats(@ForAll @IntRange(min = 0, max = 255) int formatId,
		@ForAll @Size(Fingerprint.SECRET_SIZE) byte[] secret) {

		Assume.that(Arrays.stream(StorageFormat.values()).noneMatch(format -> (format.getId() & 0xFF) == formatId));
		assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(Base58.encodeChecked(formatId, secret)));
	}

	@Property(tries = 100)
	public void rejectsWrongLengths(@ForAll @Size(max = 64) byte[] secret) {
		Assume.that(secret.length != Fingerprint.SECRET_SIZE);

		final String encoded = Base58.encodeChecked(StorageFormat.current().getId() & 0xFF, secret);
		assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(encoded));
	}

	@Example
	public void carriesTheStorageFormat() {
		final Fingerprint fingerprint = Fingerprint.generate(new SecureRandom());

		assertThat(fingerprint.format(), equalTo(StorageFormat.current()));
		assertThat(Base58.decodeChecked(fingerprint.encode())[0], equalTo(StorageFormat.current().getId()));
	}

	@Example
	public void neverPrintsItsSecret() {
		final Fingerprint fingerprint = Fingerprint.generate(new SecureRandom());

		assertThat(fingerprint.toString(), not(containsString(fingerprint.encode())));
	}
}
