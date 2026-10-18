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

package org.unigrid.hedgehog.s3.spi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple3;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.s3.jqwik.Expect.assertThrows;

public class BlobTest {
	private static final int MAX_SIZE = 4096;

	@Provide
	Arbitrary<Tuple3<byte[], Long, Long>> ranges() {
		return Arbitraries.bytes().array(byte[].class).ofMaxSize(MAX_SIZE).flatMap(content -> Arbitraries.longs()
			.between(0, content.length).flatMap(offset -> Arbitraries.longs().between(0, content.length - offset)
			.map(length -> Tuple.of(content, offset, length))));
	}

	@Property
	public void writesOnlyTheRangeAskedFor(@ForAll("ranges") final Tuple3<byte[], Long, Long> range,
		@ForAll @IntRange(min = 1, max = 64) final int piece) throws Exception {

		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		final int offset = range.get2().intValue();

		new PiecewiseBlob(range.get1(), piece).writeTo(output, offset, range.get3());
		assertThat(output.toByteArray(), equalTo(Arrays.copyOfRange(range.get1(), offset,
			offset + range.get3().intValue())));
	}

	@Property
	public void refusesARangeOutsideTheBlob(@ForAll final byte[] content, @ForAll final long offset,
		@ForAll final long length) {

		Assume.that(offset < 0 || length < 0 || length > content.length - offset);
		assertThrows(IndexOutOfBoundsException.class, () -> new PiecewiseBlob(content, 1)
			.writeTo(OutputStream.nullOutputStream(), offset, length));
	}

	/* Streams in pieces of a fixed size, single bytes included, the way a decoding store hands its output over */
	private record PiecewiseBlob(byte[] content, int piece) implements Blob {
		@Override
		public long size() {
			return content.length;
		}

		@Override
		public void writeTo(final OutputStream output) throws IOException {
			for (int position = 0; position < content.length; position += piece) {
				if (piece == 1) {
					output.write(content[position]);
				} else {
					output.write(content, position, Math.min(piece, content.length - position));
				}
			}
		}
	}
}
