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

import java.util.stream.IntStream;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class StorageLayoutTest {
	@Property(tries = 300)
	public void coversEveryByteWithoutSpareChunks(@ForAll @LongRange(min = 0, max = 50_000) long fileSize,
		@ForAll @IntRange(min = 1, max = 20) int maxOuterDataChunks) {

		final LayoutParameters parameters = LayoutParametersTest.small().toBuilder()
			.maxOuterDataChunks(maxOuterDataChunks).build();
		final StorageLayout layout = StorageLayout.of(parameters, fileSize);
		final long chunks = IntStream.range(0, layout.stripes()).mapToLong(layout::dataChunksIn).sum();

		assertThat(chunks, equalTo(layout.dataChunks()));
		assertThat(layout.dataChunks() * parameters.payloadSize(), greaterThanOrEqualTo(fileSize));

		if (fileSize > 0) {
			assertThat((layout.dataChunks() - 1) * parameters.payloadSize(), lessThan(fileSize));
		}

		IntStream.range(0, layout.stripes()).forEach(s -> assertThat(layout.dataChunksIn(s) > 0, is(true)));
	}

	@Example
	public void storesAnEmptyFileAsOneChunk() {
		final StorageLayout layout = StorageLayout.of(LayoutParametersTest.small(), 0);

		assertThat(layout.dataChunks(), equalTo(1L));
		assertThat(layout.stripes(), equalTo(1));
	}

	@Property
	public void addsNoChunkForAnExactMultiple(@ForAll @IntRange(min = 1, max = 1000) int chunks) {
		final LayoutParameters parameters = LayoutParametersTest.small();
		final StorageLayout layout = StorageLayout.of(parameters, (long) chunks * parameters.payloadSize());
		final int stripeWidth = parameters.getMaxOuterDataChunks();

		assertThat(layout.dataChunks(), equalTo((long) chunks));
		assertThat(layout.stripes(), equalTo((chunks + stripeWidth - 1) / stripeWidth));

		IntStream.range(0, layout.stripes()).forEach(s ->
			assertThat(layout.parityChunksIn(s), equalTo((layout.dataChunksIn(s) + 1) / 2))
		);
	}

	@Property
	public void rejectsNegativeFileSizes(@ForAll @LongRange(min = Long.MIN_VALUE, max = -1) long fileSize) {
		assertThrows(IllegalArgumentException.class, () -> StorageLayout.of(LayoutParametersTest.small(), fileSize));
	}
}
