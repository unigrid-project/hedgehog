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

package org.unigrid.hedgehog.model.storage;

import lombok.Builder;
import lombok.Value;
import org.unigrid.hedgehog.model.storage.crypto.ChunkCipher;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;

@Value
@Builder(toBuilder = true)
public class LayoutParameters {
	public static final int MAX_PARITY_PERCENT = 200;
	private static final int PERCENT = 100;
	private static final int MAX_FRAGMENT_PARITY_PERCENT = ReedSolomon.MAX_SHARDS * PERCENT;

	private final int chunkSize;
	private final int fragmentSize;
	private final int outerParityPercent;
	private final int maxOuterDataChunks;
	private final int innerParityPercent;
	private final int maxParityPercent;

	public static int percentOf(int value, int percent) {
		return Math.toIntExact((value * (long) percent + PERCENT - 1) / PERCENT);
	}

	public static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException(message);
		}
	}

	public static boolean inRange(int value, int minimum, int maximum) {
		return value >= minimum && value <= maximum;
	}

	public int dataFragments() {
		return chunkSize / fragmentSize;
	}

	public int parityFragments() {
		return percentOf(dataFragments(), innerParityPercent);
	}

	public int guaranteedFragments() {
		return dataFragments() + parityFragments();
	}

	public int maxFragments() {
		return dataFragments() + percentOf(dataFragments(), maxParityPercent);
	}

	public int payloadSize() {
		return chunkSize - ChunkCipher.TAG_SIZE;
	}

	public int outerParityChunks(int dataChunks) {
		return percentOf(dataChunks, outerParityPercent);
	}

	public void validate() {
		validateSizes();
		validatePercentages();
		validateShardCounts();
	}

	private void validateSizes() {
		require(fragmentSize > 0, "fragmentSize must be positive");
		require(chunkSize >= ChunkCipher.TAG_SIZE + Manifest.ENCODED_SIZE, "chunkSize cannot hold a manifest");
		require(chunkSize % fragmentSize == 0, "chunkSize must be a multiple of fragmentSize");
	}

	private void validatePercentages() {
		require(inRange(outerParityPercent, 0, MAX_PARITY_PERCENT), "outerParityPercent must be 0-200");
		require(inRange(innerParityPercent, 0, MAX_PARITY_PERCENT), "innerParityPercent must be 0-200");
		require(inRange(maxParityPercent, innerParityPercent, MAX_FRAGMENT_PARITY_PERCENT),
			"maxParityPercent must lie between innerParityPercent and 25500"
		);
	}

	/* The shard counts are bounded before any percentage is taken of them, so the arithmetic cannot overflow */
	private void validateShardCounts() {
		require(dataFragments() <= ReedSolomon.MAX_SHARDS, "A chunk would need more than 255 fragments");
		require(maxFragments() <= ReedSolomon.MAX_SHARDS, "A chunk would need more than 255 fragments");
		require(inRange(maxOuterDataChunks, 1, ReedSolomon.MAX_SHARDS), "maxOuterDataChunks must be 1-255");
		require(maxOuterDataChunks + outerParityChunks(maxOuterDataChunks) <= ReedSolomon.MAX_SHARDS,
			"A stripe would need more than 255 chunks"
		);
	}
}
