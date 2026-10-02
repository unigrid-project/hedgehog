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

package org.unigrid.hedgehog.model.bootstrap;

import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.exceptions.AddressFormatException;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LegacyAddress {
	public static final int PUBLIC_KEY_VERSION = 40;

	/*
	   A legal address encodes a one-byte version, a 20-byte hash and a 4-byte checksum, which never
	   needs more than 35 base58 characters; decoding is quadratic in its input length, so anything
	   far longer than that is rejected before it is decoded rather than after.
	*/
	private static final int MAXIMUM_ENCODED_LENGTH = 50;

	public static String encode(byte[] addressHash) {
		return Base58.encodeChecked(PUBLIC_KEY_VERSION, addressHash);
	}

	public static byte[] decode(String address) {
		final byte[] versioned = decodeChecked(address);

		if (versioned.length == 0) {
			throw new IllegalArgumentException("Address is too short: " + address);
		}

		if (Byte.toUnsignedInt(versioned[0]) != PUBLIC_KEY_VERSION) {
			throw new IllegalArgumentException("Address is not for this network: " + address);
		}

		final byte[] hash = Arrays.copyOfRange(versioned, 1, versioned.length);

		if (hash.length != Hashing.ADDRESS_HASH_SIZE) {
			throw new IllegalArgumentException("Address payload is not a hash160: " + address);
		}

		return hash;
	}

	private static byte[] decodeChecked(String address) {
		if (address.length() > MAXIMUM_ENCODED_LENGTH) {
			throw new IllegalArgumentException("Address is too long: " + address.length() + " characters");
		}

		try {
			return Base58.decodeChecked(address);

		} catch (AddressFormatException.InvalidCharacter ex) {
			throw new IllegalArgumentException("Not a base58 character: " + ex.character, ex);
		} catch (AddressFormatException.InvalidChecksum ex) {
			throw new IllegalArgumentException("Address checksum does not match: " + address, ex);
		} catch (AddressFormatException.InvalidDataLength ex) {
			throw new IllegalArgumentException("Address is too short: " + address, ex);
		}
	}
}
