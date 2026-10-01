/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import java.security.SecureRandom;
import java.util.Arrays;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import org.unigrid.hedgehog.model.storage.crypto.Fingerprints.Secrets;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Chars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import org.bitcoinj.core.Base58;
import org.unigrid.hedgehog.model.storage.StorageFormat;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class FingerprintTest {
	private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	private static final String KNOWN_ENCODING = "2wkH4kHMn2WPndf8CxmsoFkX93ouZMJUwTBFSZpDCeNeGWa7dj";

	private static SecureRandom replaying(byte[] bytes) {
		return new SecureRandom() {
			@Override
			public void nextBytes(byte[] target) {
				System.arraycopy(bytes, 0, target, 0, target.length);
			}
		};
	}

	@Property(tries = 50)
	public void roundTripsThroughText(@ForAll(supplier = Secrets.class) byte[] secret) {
		final Fingerprint fingerprint = Fingerprint.generate(replaying(secret));

		assertThat(Fingerprint.parse(fingerprint.encode()), equalTo(fingerprint));
	}

	@Property(tries = 50)
	public void parsesWithSurroundingWhitespace(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @Chars({' ', '\t', '\n', '\r'}) @StringLength(max = 4) String leading,
		@ForAll @Chars({' ', '\t', '\n', '\r'}) @StringLength(max = 4) String trailing) {

		final Fingerprint fingerprint = Fingerprint.generate(replaying(secret));

		assertThat(Fingerprint.parse(leading + fingerprint.encode() + trailing), equalTo(fingerprint));
	}

	@Property(tries = 200)
	public void rejectsSingleCharacterTypos(@ForAll(supplier = Secrets.class) byte[] secret,
		@ForAll @IntRange(min = 0, max = 60) int position, @ForAll @IntRange(min = 1, max = 57) int shift) {

		final String encoded = Fingerprints.of(secret).encode();
		final int index = position % encoded.length();
		final int shifted = (ALPHABET.indexOf(encoded.charAt(index)) + shift) % ALPHABET.length();
		final String typo = encoded.substring(0, index) + ALPHABET.charAt(shifted) + encoded.substring(index + 1);

		assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(typo));
	}

	@Property(tries = 100)
	public void rejectsUnknownFormats(@ForAll @IntRange(min = 0, max = 255) int formatId,
		@ForAll(supplier = Secrets.class) byte[] secret) {

		Assume.that(Arrays.stream(StorageFormat.values()).noneMatch(format -> (format.getId() & 0xFF) == formatId));

		final String encoded = Base58.encodeChecked(formatId, secret);
		assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(encoded));
	}

	@Property(tries = 100)
	public void rejectsWrongLengths(@ForAll @Size(max = 64) byte[] secret) {
		Assume.that(secret.length != Fingerprint.SECRET_SIZE);

		final String encoded = Base58.encodeChecked(StorageFormat.current().getId() & 0xFF, secret);
		assertThrows(IllegalArgumentException.class, () -> Fingerprint.parse(encoded));
	}

	@Property(tries = 50)
	public void generatesTheCurrentFormatFromTheGivenRandomness(@ForAll(supplier = Secrets.class) byte[] secret) {
		final Fingerprint fingerprint = Fingerprint.generate(replaying(secret));

		assertThat(fingerprint.format(), equalTo(StorageFormat.current()));
		assertThat(fingerprint, equalTo(Fingerprints.of(secret)));
		assertThat(Base58.decodeChecked(fingerprint.encode())[0], equalTo(StorageFormat.current().getId()));
	}

	@Property(tries = 50)
	public void neverPrintsItsSecret(@ForAll(supplier = Secrets.class) byte[] secret) {
		final Fingerprint fingerprint = Fingerprints.of(secret);

		assertThat(fingerprint.toString(), not(containsString(fingerprint.encode())));
	}

	@Example
	public void encodesTheKnownSecretStably() {
		final Fingerprint fingerprint = Fingerprint.generate(replaying(Fingerprints.knownSecret()));

		assertThat(fingerprint.encode(), equalTo(KNOWN_ENCODING));
		assertThat(Fingerprint.parse(KNOWN_ENCODING), equalTo(fingerprint));
	}
}
