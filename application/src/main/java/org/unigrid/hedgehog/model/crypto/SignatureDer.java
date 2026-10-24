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

package org.unigrid.hedgehog.model.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.Validate;

/*
    An ECDSA signature as DER writes it, the length of the sequence in one byte, or in two for a signature of P-521:
    [ 0x30 ][ length, or 0x81 and length ][ 0x02 ][ length ][      r      ][ 0x02 ][ length ][      s      ]
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SignatureDer {
	private static final int SEQUENCE = 0x30;
	private static final int INTEGER = 0x02;
	private static final int LENGTH_IN_A_BYTE = 0x81;
	private static final int SHORT_LENGTHS = 0x80;
	private static final int BYTE = 0xff;

	/* The r and the s of a signature, read however long its lengths are written, or nothing for what is no DER of two
	   integers that ends where the sequence does */
	static Optional<BigInteger[]> decoded(byte[] der) {
		if (Objects.isNull(der)) {
			return Optional.empty();
		}

		try {
			final ByteBuffer in = ByteBuffer.wrap(der);

			Validate.isTrue(in.get() == SEQUENCE);
			Validate.isTrue(lengthOf(in) == in.remaining());

			final BigInteger[] rs = { integerOf(in), integerOf(in) };

			Validate.isTrue(!in.hasRemaining());
			return Optional.of(rs);
		} catch (IllegalArgumentException | BufferUnderflowException ex) {
			return Optional.empty();
		}
	}

	/* In a byte, or in the byte after 0x81 */
	private static int lengthOf(ByteBuffer in) {
		final int first = in.get() & BYTE;

		Validate.isTrue(first != SHORT_LENGTHS && first <= LENGTH_IN_A_BYTE);
		return first == LENGTH_IN_A_BYTE ? in.get() & BYTE : first;
	}

	private static BigInteger integerOf(ByteBuffer in) {
		Validate.isTrue(in.get() == INTEGER);

		final int length = in.get() & BYTE;

		Validate.isTrue(length > 0 && length < SHORT_LENGTHS);

		final byte[] value = new byte[length];

		in.get(value);
		return new BigInteger(value);
	}

	/* In the shortest form that DER has */
	static byte[] encoded(BigInteger r, BigInteger s) {
		final byte[] rBytes = r.toByteArray();
		final byte[] sBytes = s.toByteArray();
		final int content = 2 + rBytes.length + 2 + sBytes.length;
		final ByteArrayOutputStream out = new ByteArrayOutputStream();

		out.write(SEQUENCE);

		if (content >= SHORT_LENGTHS) {
			out.write(LENGTH_IN_A_BYTE);
		}

		out.write(content);

		for (byte[] value : new byte[][] { rBytes, sBytes }) {
			out.write(INTEGER);
			out.write(value.length);
			out.writeBytes(value);
		}

		return out.toByteArray();
	}
}
