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

import java.math.BigInteger;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class Base58Check {
	private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	private static final BigInteger RADIX = BigInteger.valueOf(ALPHABET.length());
	private static final int CHECKSUM_SIZE = 4;

	public static String encode(int version, byte[] payload) {
		final byte[] versioned = new byte[payload.length + 1];

		versioned[0] = (byte) version;
		System.arraycopy(payload, 0, versioned, 1, payload.length);
		return encodeRaw(concatenateChecksum(versioned));
	}

	public static byte[] decode(int version, String encoded) {
		final byte[] raw = decodeRaw(encoded);

		if (raw.length < CHECKSUM_SIZE + 1) {
			throw new IllegalArgumentException("Address is too short: " + encoded);
		}

		final byte[] versioned = Arrays.copyOf(raw, raw.length - CHECKSUM_SIZE);
		final byte[] expected = Arrays.copyOf(Hashing.checksum(versioned), CHECKSUM_SIZE);

		if (!Arrays.equals(expected, Arrays.copyOfRange(raw, versioned.length, raw.length))) {
			throw new IllegalArgumentException("Address checksum does not match: " + encoded);
		}

		if (Byte.toUnsignedInt(versioned[0]) != version) {
			throw new IllegalArgumentException("Address is not for this network: " + encoded);
		}

		return Arrays.copyOfRange(versioned, 1, versioned.length);
	}

	private static byte[] concatenateChecksum(byte[] versioned) {
		final byte[] result = Arrays.copyOf(versioned, versioned.length + CHECKSUM_SIZE);

		System.arraycopy(Hashing.checksum(versioned), 0, result, versioned.length, CHECKSUM_SIZE);
		return result;
	}

	private static String encodeRaw(byte[] input) {
		final StringBuilder builder = new StringBuilder();
		BigInteger remaining = new BigInteger(1, input);

		while (remaining.signum() > 0) {
			final BigInteger[] division = remaining.divideAndRemainder(RADIX);

			builder.append(ALPHABET.charAt(division[1].intValue()));
			remaining = division[0];
		}

		for (int i = 0; i < input.length && input[i] == 0; i++) {
			builder.append(ALPHABET.charAt(0));
		}

		return builder.reverse().toString();
	}

	private static byte[] decodeRaw(String encoded) {
		BigInteger value = BigInteger.ZERO;

		for (int i = 0; i < encoded.length(); i++) {
			final int digit = ALPHABET.indexOf(encoded.charAt(i));

			if (digit < 0) {
				throw new IllegalArgumentException("Not a base58 character: " + encoded.charAt(i));
			}

			value = value.multiply(RADIX).add(BigInteger.valueOf(digit));
		}

		return restoreLeadingZeroes(encoded, value.toByteArray());
	}

	private static byte[] restoreLeadingZeroes(String encoded, byte[] magnitude) {
		int leading = 0;

		while (leading < encoded.length() && encoded.charAt(leading) == ALPHABET.charAt(0)) {
			leading++;
		}

		final int start = magnitude.length > 0 && magnitude[0] == 0 ? 1 : 0;
		final byte[] result = new byte[leading + magnitude.length - start];

		System.arraycopy(magnitude, start, result, leading, magnitude.length - start);
		return result;
	}
}
