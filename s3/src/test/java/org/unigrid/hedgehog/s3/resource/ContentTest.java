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

package org.unigrid.hedgehog.s3.resource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.s3.index.BlobRef;
import org.unigrid.hedgehog.s3.index.Part;
import org.unigrid.hedgehog.s3.spi.MemoryBlobStore;

/* Holds what a range of an object streams against the same range of its parts laid end to end */
public class ContentTest {
	private static final int BLOB_BYTES = 64;

	@Provide
	Arbitrary<List<List<int[]>>> parts() {
		return Arbitraries.integers().between(0, BLOB_BYTES).list().ofSize(2).map(ends -> new int[] {
			Math.min(ends.get(0), ends.get(1)), Math.max(ends.get(0), ends.get(1))
		}).list().ofMaxSize(4).list().ofMaxSize(4);
	}

	@Property
	public void streamsTheRangeOfThePartsLaidEndToEnd(@ForAll("parts") List<List<int[]>> layout,
		@ForAll @IntRange(max = 1100) int first, @ForAll @IntRange(max = 1100) int count) throws Exception {

		final MemoryBlobStore blobs = new MemoryBlobStore();
		final byte[] source = new byte[BLOB_BYTES];
		final ByteArrayOutputStream whole = new ByteArrayOutputStream();
		final List<Part> parts = new ArrayList<>();

		for (int i = 0; i < BLOB_BYTES; i++) {
			source[i] = (byte) i;
		}

		for (List<int[]> slices : layout) {
			final List<BlobRef> refs = new ArrayList<>();

			for (int[] slice : slices) {
				refs.add(new BlobRef(blobs.store(new ByteArrayInputStream(source)), slice[0], slice[1] - slice[0]));
				whole.write(source, slice[0], slice[1] - slice[0]);
			}

			parts.add(Part.builder().number(parts.size() + 1).slices(refs).build());
		}

		final byte[] expected = whole.toByteArray();
		final int offset = Math.min(first, expected.length);
		final int length = Math.min(count, expected.length - offset);
		final ByteArrayOutputStream streamed = new ByteArrayOutputStream();

		Content.of(blobs, parts, offset, length).write(streamed);
		assertThat(streamed.toByteArray(), equalTo(Arrays.copyOfRange(expected, offset, offset + length)));
	}
}
