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

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.unigrid.hedgehog.model.storage.StorageFormat;

public final class Fingerprint {
	public static final int SECRET_SIZE = 32;

	@Getter @Accessors(fluent = true)
	private final StorageFormat format;
	private final byte[] secret;

	private Fingerprint(StorageFormat format, byte[] secret) {
		this.format = format;
		this.secret = secret;
	}

	public static Fingerprint generate(SecureRandom random) {
		final byte[] secret = new byte[SECRET_SIZE];

		random.nextBytes(secret);
		return new Fingerprint(StorageFormat.current(), secret);
	}

	public static Fingerprint parse(String encoded) {
		final byte[] decoded = decode(encoded.strip());

		if (decoded.length != SECRET_SIZE + 1) {
			throw new IllegalArgumentException("Fingerprint has the wrong length");
		}

		return new Fingerprint(StorageFormat.of(decoded[0] & 0xFF), Arrays.copyOfRange(decoded, 1, decoded.length));
	}

	private static byte[] decode(String encoded) {
		try {
			return Base58.decodeChecked(encoded);
		} catch (AddressFormatException ex) {
			throw new IllegalArgumentException("Malformed fingerprint", ex);
		}
	}

	public String encode() {
		return Base58.encodeChecked(format.getId() & 0xFF, secret);
	}

	byte[] secret() {
		return secret.clone();
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Fingerprint && format == ((Fingerprint) other).format
			&& MessageDigest.isEqual(secret, ((Fingerprint) other).secret);
	}

	@Override
	public int hashCode() {
		return format.hashCode();
	}

	@Override
	public String toString() {
		return "Fingerprint[redacted]";
	}
}
