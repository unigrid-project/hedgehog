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
import java.security.AlgorithmParameters;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.ArrayUtils;

/* Other forms of a signature of P-521 that verify the same, as whoever holds one can make them: the other value of s,
   and r written with a byte more than it takes */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SignatureForms {
	private static final int LONG_LENGTH = 0x81;
	private static final int SHORT_LENGTHS = 0x80;
	private static final int SEQUENCE = 0x30;
	private static final int INTEGER = 0x02;
	private static final BigInteger ORDER = order();

	public static byte[] withTheOtherS(byte[] der) {
		final BigInteger[] rs = decoded(der);

		return encoded(rs[0].toByteArray(), ORDER.subtract(rs[1]).toByteArray());
	}

	public static byte[] withAPaddedR(byte[] der) {
		final BigInteger[] rs = decoded(der);

		return encoded(ArrayUtils.insert(0, rs[0].toByteArray(), (byte) 0), rs[1].toByteArray());
	}

	private static BigInteger[] decoded(byte[] der) {
		int at = der[1] == (byte) LONG_LENGTH ? 3 : 2;
		final BigInteger[] rs = new BigInteger[2];

		for (int i = 0; i < rs.length; i++) {
			final int length = der[at + 1];

			rs[i] = new BigInteger(Arrays.copyOfRange(der, at + 2, at + 2 + length));
			at += 2 + length;
		}

		return rs;
	}

	private static byte[] encoded(byte[] r, byte[] s) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final int content = 2 + r.length + 2 + s.length;

		out.write(SEQUENCE);

		if (content >= SHORT_LENGTHS) {
			out.write(LONG_LENGTH);
		}

		out.write(content);

		for (byte[] value : new byte[][] { r, s }) {
			out.write(INTEGER);
			out.write(value.length);
			out.writeBytes(value);
		}

		return out.toByteArray();
	}

	@SneakyThrows
	private static BigInteger order() {
		final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");

		parameters.init(new ECGenParameterSpec("secp521r1"));
		return parameters.getParameterSpec(ECParameterSpec.class).getOrder();
	}
}
