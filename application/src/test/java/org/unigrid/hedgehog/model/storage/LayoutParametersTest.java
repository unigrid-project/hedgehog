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

import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.unigrid.hedgehog.model.storage.crypto.ChunkCipher;
import org.unigrid.hedgehog.model.storage.erasure.ReedSolomon;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class LayoutParametersTest {
	private static final int SMALL_CHUNK_SIZE = 1024;

	public static LayoutParameters small() {
		return LayoutParameters.builder().chunkSize(SMALL_CHUNK_SIZE).fragmentSize(128).outerParityPercent(50)
			.maxOuterDataChunks(4).innerParityPercent(50).maxParityPercent(100).build();
	}

	private static boolean accepts(LayoutParameters layout) {
		try {
			layout.validate();
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Example
	public void derivesFragmentCounts() {
		final LayoutParameters layout = small();

		assertThat(layout.dataFragments(), equalTo(8));
		assertThat(layout.parityFragments(), equalTo(4));
		assertThat(layout.guaranteedFragments(), equalTo(12));
		assertThat(layout.maxFragments(), equalTo(16));
		assertThat(layout.payloadSize(), equalTo(1008));
		assertThat(layout.outerParityChunks(3), equalTo(2));
	}

	@Property
	public void percentOfRoundsUp(@ForAll @IntRange(min = 0, max = 1 << 20) int value,
		@ForAll @IntRange(min = 0, max = LayoutParameters.MAX_PARITY_PERCENT) int percent) {

		final long share = LayoutParameters.percentOf(value, percent);

		assertThat(share * 100, greaterThanOrEqualTo((long) value * percent));
		assertThat((share - 1) * 100, lessThan((long) value * percent));
	}

	@Property
	public void rejectsFragmentSizesThatDoNotDivideTheChunk(@ForAll @IntRange(min = 1, max = SMALL_CHUNK_SIZE)
		int fragmentSize) {

		Assume.that(SMALL_CHUNK_SIZE % fragmentSize != 0);
		assertRejected(small().toBuilder().fragmentSize(fragmentSize).build());
	}

	@Property
	public void rejectsNonPositiveFragmentSizes(@ForAll @IntRange(min = Integer.MIN_VALUE, max = 0) int fragmentSize) {
		assertRejected(small().toBuilder().fragmentSize(fragmentSize).build());
	}

	@Property
	public void rejectsChunksTooSmallForAManifest(@ForAll @IntRange(min = 1,
		max = ChunkCipher.TAG_SIZE + Manifest.ENCODED_SIZE - 1) int chunkSize) {

		assertRejected(small().toBuilder().chunkSize(chunkSize).fragmentSize(chunkSize).build());
	}

	@Property
	public void acceptsExactlyTheFragmentCountsReedSolomonCanCode(@ForAll @IntRange(min = 1, max = 255)
		int dataFragments, @ForAll @IntRange(min = 50, max = LayoutParameters.MAX_PARITY_PERCENT) int maxParityPercent) {

		final int fragmentSize = 64;
		final LayoutParameters layout = small().toBuilder().chunkSize(dataFragments * fragmentSize)
			.fragmentSize(fragmentSize).maxParityPercent(maxParityPercent).build();
		final boolean codable = dataFragments + LayoutParameters.percentOf(dataFragments, maxParityPercent)
			<= ReedSolomon.MAX_SHARDS;

		assertThat(accepts(layout), is(codable));
	}

	@Property
	public void acceptsExactlyTheStripesReedSolomonCanCode(@ForAll @IntRange(min = -10, max = 300)
		int maxOuterDataChunks, @ForAll @IntRange(min = -50, max = 300) int outerParityPercent) {

		final LayoutParameters layout = small().toBuilder().maxOuterDataChunks(maxOuterDataChunks)
			.outerParityPercent(outerParityPercent).build();
		final boolean codable = outerParityPercent >= 0 && outerParityPercent <= LayoutParameters.MAX_PARITY_PERCENT
			&& maxOuterDataChunks >= 1
			&& maxOuterDataChunks + LayoutParameters.percentOf(maxOuterDataChunks, outerParityPercent)
			<= ReedSolomon.MAX_SHARDS;

		assertThat(accepts(layout), is(codable));
	}

	@Property
	public void acceptsExactlyTheParityPercentagesInRange(@ForAll @IntRange(min = -50, max = 300) int innerParityPercent,
		@ForAll @IntRange(min = -50, max = 300) int maxParityPercent) {

		final LayoutParameters layout = small().toBuilder().innerParityPercent(innerParityPercent)
			.maxParityPercent(maxParityPercent).build();
		final boolean consistent = innerParityPercent >= 0 && innerParityPercent <= LayoutParameters.MAX_PARITY_PERCENT
			&& maxParityPercent >= innerParityPercent;

		assertThat(accepts(layout), is(consistent));
	}

	@Example
	public void acceptsTheSmallLayout() {
		small().validate();
	}

	private static void assertRejected(LayoutParameters layout) {
		assertThrows(IllegalArgumentException.class, layout::validate);
	}
}
