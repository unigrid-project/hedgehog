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

package org.unigrid.hedgehog.model.storage.erasure;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.IntStream;

public final class ReedSolomon {
	public static final int MAX_SHARDS = 255;

	private final int dataShards;
	private final int parityShards;
	private final int[][] parityRows;

	public ReedSolomon(final int dataShards, final int parityShards) {
		if (dataShards < 1 || parityShards < 0 || dataShards + parityShards > MAX_SHARDS) {
			throw new IllegalArgumentException("Unsupported shard counts " + dataShards + "+" + parityShards);
		}

		this.dataShards = dataShards;
		this.parityShards = parityShards;
		parityRows = new int[parityShards][dataShards];

		for (int row = 0; row < parityShards; row++) {
			for (int column = 0; column < dataShards; column++) {
				parityRows[row][column] = cauchy(row, column);
			}
		}
	}

	/* Rows depend only on the absolute shard position, which keeps a smaller code a prefix of a larger one
	   and lets extra parity be added later without touching the guaranteed shards. */
	private int cauchy(final int parityIndex, final int column) {
		return GaloisField.inverse((dataShards + parityIndex) ^ column);
	}

	public int totalShards() {
		return dataShards + parityShards;
	}

	public byte[][] encode(final byte[][] data) {
		requireCountOfEqualSize(data, dataShards);

		final byte[][] parity = new byte[parityShards][data[0].length];

		for (int row = 0; row < parityShards; row++) {
			multiplyInto(parityRows[row], data, parity[row]);
		}

		return parity;
	}

	public byte[][] decode(final byte[][] shards, final boolean[] present) {
		if (shards.length != totalShards() || present.length != totalShards()) {
			throw new IllegalArgumentException("Expected " + totalShards() + " shard slots");
		}

		final int[] available = IntStream.range(0, totalShards()).filter(i -> present[i]).toArray();

		if (available.length < dataShards) {
			throw new IllegalArgumentException("Need " + dataShards + " shards but only "
				+ available.length + " are present");
		}

		final byte[][] availableShards = Arrays.stream(available).mapToObj(i -> shards[i]).toArray(byte[][]::new);

		requireNonNullOfEqualSize(availableShards);

		final byte[][] sources = Arrays.copyOf(availableShards, dataShards);
		final int[][] inverse = invert(rowsOf(Arrays.copyOf(available, dataShards)));
		final byte[][] data = new byte[dataShards][sources[0].length];

		for (int row = 0; row < dataShards; row++) {
			multiplyInto(inverse[row], sources, data[row]);
		}

		final byte[][] result = Arrays.copyOf(data, totalShards());
		System.arraycopy(encode(data), 0, result, dataShards, parityShards);
		return result;
	}

	private int[][] rowsOf(final int[] shardIndices) {
		return Arrays.stream(shardIndices).mapToObj(this::rowOf).toArray(int[][]::new);
	}

	private int[] rowOf(final int shardIndex) {
		if (shardIndex >= dataShards) {
			return parityRows[shardIndex - dataShards].clone();
		}

		final int[] unit = new int[dataShards];
		unit[shardIndex] = 1;
		return unit;
	}

	private static void requireCountOfEqualSize(final byte[][] shards, final int expectedCount) {
		if (shards.length != expectedCount) {
			throw new IllegalArgumentException("Expected " + expectedCount + " shards");
		}

		requireNonNullOfEqualSize(shards);
	}

	private static void requireNonNullOfEqualSize(final byte[][] shards) {
		if (Arrays.stream(shards).anyMatch(Objects::isNull)
			|| Arrays.stream(shards).mapToInt(s -> s.length).distinct().count() != 1) {
			throw new IllegalArgumentException("Expected non-null shards of equal size");
		}
	}

	private static void multiplyInto(final int[] coefficients, final byte[][] sources, final byte[] target) {
		for (int column = 0; column < coefficients.length; column++) {
			final byte[] products = GaloisField.productsOf(coefficients[column]);
			final byte[] source = sources[column];

			for (int position = 0; position < target.length; position++) {
				target[position] ^= products[source[position] & 0xFF];
			}
		}
	}

	private static int[][] invert(final int[][] matrix) {
		final int size = matrix.length;
		final int[][] work = new int[size][size * 2];

		for (int row = 0; row < size; row++) {
			System.arraycopy(matrix[row], 0, work[row], 0, size);
			work[row][size + row] = 1;
		}

		for (int column = 0; column < size; column++) {
			pivot(work, column);
			eliminate(work, column);
		}

		return Arrays.stream(work).map(row -> Arrays.copyOfRange(row, size, size * 2)).toArray(int[][]::new);
	}

	private static void pivot(final int[][] work, final int column) {
		int row = column;

		while (row < work.length && work[row][column] == 0) {
			row++;
		}

		if (row == work.length) {
			throw new ArithmeticException("Singular decoding matrix");
		}

		final int[] swap = work[row];
		work[row] = work[column];
		work[column] = swap;

		final int scale = GaloisField.inverse(work[column][column]);

		for (int c = 0; c < work[column].length; c++) {
			work[column][c] = GaloisField.multiply(work[column][c], scale);
		}
	}

	private static void eliminate(final int[][] work, final int column) {
		for (int row = 0; row < work.length; row++) {
			final int factor = work[row][column];

			if (row != column && factor != 0) {
				for (int c = 0; c < work[row].length; c++) {
					work[row][c] ^= GaloisField.multiply(factor, work[column][c]);
				}
			}
		}
	}
}
