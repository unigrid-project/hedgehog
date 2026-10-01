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

package org.unigrid.hedgehog.model.storage.erasure;

final class GaloisField {
	private static final int FIELD_SIZE = 256;
	private static final int GENERATOR_POLYNOMIAL = 0x11D;
	private static final int[] EXP = new int[FIELD_SIZE * 2];
	private static final int[] LOG = new int[FIELD_SIZE];
	private static final byte[][] PRODUCTS = new byte[FIELD_SIZE][FIELD_SIZE];

	static {
		int element = 1;

		for (int power = 0; power < FIELD_SIZE - 1; power++) {
			EXP[power] = element;
			LOG[element] = power;
			element = next(element);
		}

		for (int power = FIELD_SIZE - 1; power < EXP.length; power++) {
			EXP[power] = EXP[power - (FIELD_SIZE - 1)];
		}

		for (int a = 1; a < FIELD_SIZE; a++) {
			for (int b = 1; b < FIELD_SIZE; b++) {
				PRODUCTS[a][b] = (byte) EXP[LOG[a] + LOG[b]];
			}
		}
	}

	private GaloisField() {
	}

	private static int next(final int element) {
		final int shifted = element << 1;
		return shifted >= FIELD_SIZE ? shifted ^ GENERATOR_POLYNOMIAL : shifted;
	}

	static int multiply(final int a, final int b) {
		return PRODUCTS[a][b] & 0xFF;
	}

	static byte[] productsOf(final int a) {
		return PRODUCTS[a];
	}

	static int inverse(final int a) {
		if (a == 0) {
			throw new ArithmeticException("Zero has no multiplicative inverse");
		}

		return EXP[FIELD_SIZE - 1 - LOG[a]];
	}
}
