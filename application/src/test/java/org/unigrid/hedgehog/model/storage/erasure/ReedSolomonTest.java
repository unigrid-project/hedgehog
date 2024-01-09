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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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

	@Property(tries = 300)
	public void recoversAnyErasureWithinParity(@ForAll @IntRange(min = 1, max = 20) int dataShards,
		@ForAll @IntRange(min = 0, max = 12) int parityShards, @ForAll @IntRange(min = 1, max = 64) int size,
		@ForAll long seed) {

		final Random random = new Random(seed);
		final ReedSolomon codec = new ReedSolomon(dataShards, parityShards);
		final byte[][] data = randomShards(random, dataShards, size);
		final byte[][] original = concat(data, codec.encode(data));
		final byte[][] shards = deepCopy(original);
		final boolean[] present = new boolean[shards.length];

		Arrays.fill(present, true);

		shuffledPositions(random, shards.length).stream().limit(random.nextInt(parityShards + 1)).forEach(lost -> {
			present[lost] = false;
			shards[lost] = null;
		});

		final byte[][] decoded = codec.decode(shards, present);

		for (int i = 0; i < original.length; i++) {
			assertThat(decoded[i], equalTo(original[i]));
		}
	}

	@Property(tries = 100)
	public void smallerCodesArePrefixesOfLargerOnes(@ForAll @IntRange(min = 1, max = 16) int dataShards,
		@ForAll @IntRange(min = 0, max = 8) int smaller, @ForAll @IntRange(min = 0, max = 8) int extra,
		@ForAll long seed) {

		final byte[][] data = randomShards(new Random(seed), dataShards, 16);
		final byte[][] small = new ReedSolomon(dataShards, smaller).encode(data);
		final byte[][] large = new ReedSolomon(dataShards, smaller + extra).encode(data);

		for (int i = 0; i < smaller; i++) {
			assertThat(large[i], equalTo(small[i]));
		}
	}

	@Property(tries = 100)
	public void refusesTooFewShards(@ForAll @IntRange(min = 1, max = 20) int dataShards,
		@ForAll @IntRange(min = 0, max = 12) int parityShards, @ForAll long seed) {

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
	public void refusesMoreShardsThanTheFieldAllows(@ForAll @IntRange(min = 1, max = 255) int dataShards,
		@ForAll @IntRange(min = 1, max = 255) int excess) {

		final int parityShards = ReedSolomon.MAX_SHARDS - dataShards + excess;
		assertThrows(IllegalArgumentException.class, () -> new ReedSolomon(dataShards, parityShards));
	}
}
