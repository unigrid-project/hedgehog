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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ReedSolomonTest {
	private static byte[][] randomShards(final Random random, final int count, final int size) {
		final byte[][] shards = new byte[count][size];

		for (final byte[] shard : shards) {
			random.nextBytes(shard);
		}

		return shards;
	}

	private static byte[][] concat(final byte[][] data, final byte[][] parity) {
		return Stream.concat(Arrays.stream(data), Arrays.stream(parity)).toArray(byte[][]::new);
	}

	private static byte[][] deepCopy(final byte[][] shards) {
		return Arrays.stream(shards).map(byte[]::clone).toArray(byte[][]::new);
	}

	private static List<Integer> shuffledPositions(final Random random, final int count) {
		final List<Integer> positions = IntStream.range(0, count).boxed()
			.collect(Collectors.toCollection(ArrayList::new));

		Collections.shuffle(positions, random);
		return positions;
	}

	private static boolean[] eraseRandomly(final Random random, final byte[][] shards, final int maxErasures) {
		final boolean[] present = new boolean[shards.length];

		Arrays.fill(present, true);

		shuffledPositions(random, shards.length).stream().limit(random.nextInt(maxErasures + 1)).forEach(lost -> {
			present[lost] = false;
			shards[lost] = null;
		});

		return present;
	}

	@Property(tries = 300)
	public void recoversAnyErasureWithinParity(@ForAll @IntRange(min = 1, max = 20) final int dataShards,
		@ForAll @IntRange(min = 0, max = 12) final int parityShards,
		@ForAll @IntRange(min = 1, max = 64) final int size, @ForAll final long seed) {

		final Random random = new Random(seed);
		final ReedSolomon codec = new ReedSolomon(dataShards, parityShards);
		final byte[][] data = randomShards(random, dataShards, size);
		final byte[][] original = concat(data, codec.encode(data));
		final byte[][] shards = deepCopy(original);
		final boolean[] present = eraseRandomly(random, shards, parityShards);
		final byte[][] decoded = codec.decode(shards, present);

		for (int i = 0; i < original.length; i++) {
			assertThat(decoded[i], equalTo(original[i]));
		}
	}

	@Property(tries = 100)
	public void smallerCodesArePrefixesOfLargerOnes(@ForAll @IntRange(min = 1, max = 16) final int dataShards,
		@ForAll @IntRange(min = 0, max = 8) final int smaller, @ForAll @IntRange(min = 0, max = 8) final int extra,
		@ForAll final long seed) {

		final byte[][] data = randomShards(new Random(seed), dataShards, 16);
		final byte[][] small = new ReedSolomon(dataShards, smaller).encode(data);
		final byte[][] large = new ReedSolomon(dataShards, smaller + extra).encode(data);

		for (int i = 0; i < smaller; i++) {
			assertThat(large[i], equalTo(small[i]));
		}
	}

	@Property(tries = 100)
	public void smallerCodesDecodeFragmentsOfLargerOnes(@ForAll @IntRange(min = 1, max = 16) final int dataShards,
		@ForAll @IntRange(min = 0, max = 8) final int smaller, @ForAll @IntRange(min = 0, max = 8) final int extra,
		@ForAll @IntRange(min = 1, max = 32) final int size, @ForAll final long seed) {

		final Random random = new Random(seed);
		final byte[][] data = randomShards(random, dataShards, size);
		final byte[][] largeParity = new ReedSolomon(dataShards, smaller + extra).encode(data);
		final byte[][] shards = concat(deepCopy(data), Arrays.copyOf(largeParity, smaller));
		final boolean[] present = eraseRandomly(random, shards, smaller);
		final byte[][] decoded = new ReedSolomon(dataShards, smaller).decode(shards, present);

		for (int i = 0; i < dataShards; i++) {
			assertThat(decoded[i], equalTo(data[i]));
		}
	}

	@Example
	public void keepsTheEncodingMatrixStable() {
		final byte[][] data = {
			{ 0x00, 0x01, 0x02, 0x03 },
			{ 0x10, 0x20, 0x40, (byte) 0x80 },
			{ (byte) 0xFF, 0x55, (byte) 0xAA, 0x0F }
		};

		final byte[][] expected = {
			{ (byte) 0xF7, (byte) 0xB1, 0x7F, 0x4E },
			{ 0x06, (byte) 0x89, 0x0F, 0x3C }
		};

		assertThat(new ReedSolomon(3, 2).encode(data), equalTo(expected));
	}

	@Property(tries = 200)
	public void refusesMalformedPresentShards(@ForAll @IntRange(min = 1, max = 20) final int dataShards,
		@ForAll @IntRange(min = 1, max = 12) final int parityShards,
		@ForAll @IntRange(min = 1, max = 64) final int size, @ForAll final boolean missing,
		@ForAll final long seed) {

		final Random random = new Random(seed);
		final ReedSolomon codec = new ReedSolomon(dataShards, parityShards);
		final byte[][] data = randomShards(random, dataShards, size);
		final byte[][] shards = concat(data, codec.encode(data));
		final boolean[] present = new boolean[shards.length];
		final int wrongLength = random.nextBoolean() ? random.nextInt(size) : size + 1 + random.nextInt(8);

		Arrays.fill(present, true);
		shards[random.nextInt(shards.length)] = missing ? null : new byte[wrongLength];
		assertThrows(IllegalArgumentException.class, () -> codec.decode(shards, present));
	}

	@Property(tries = 100)
	public void refusesTooFewShards(@ForAll @IntRange(min = 1, max = 20) final int dataShards,
		@ForAll @IntRange(min = 0, max = 12) final int parityShards, @ForAll final long seed) {

		final Random random = new Random(seed);
		final ReedSolomon codec = new ReedSolomon(dataShards, parityShards);
		final byte[][] shards = new byte[codec.totalShards()][];
		final boolean[] present = new boolean[shards.length];

		shuffledPositions(random, shards.length).stream().limit(random.nextInt(dataShards)).forEach(kept -> {
			present[kept] = true;
			shards[kept] = new byte[4];
		});

		assertThrows(IllegalArgumentException.class, () -> codec.decode(shards, present));
	}

	@Property(tries = 100)
	public void refusesMoreShardsThanTheFieldAllows(@ForAll @IntRange(min = 1, max = 255) final int dataShards,
		@ForAll @IntRange(min = 1, max = 255) final int excess) {

		final int parityShards = ReedSolomon.MAX_SHARDS - dataShards + excess;
		assertThrows(IllegalArgumentException.class, () -> new ReedSolomon(dataShards, parityShards));
	}
}

