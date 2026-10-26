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

package org.unigrid.hedgehog.s3.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class TeeInputStreamTest {
	private static TeeInputStream tee(final byte[] content, final OutputStream copy, final long limit) {
		return new TeeInputStream(new ByteArrayInputStream(content), copy, limit);
	}

	private static byte[] readByteByByte(final TeeInputStream tee) throws IOException {
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		for (int value = tee.read(); value >= 0; value = tee.read()) {
			output.write(value);
		}

		return output.toByteArray();
	}

	@Property
	public void copiesEverythingItPassesOn(@ForAll byte[] content, @ForAll boolean byteByByte) throws Exception {
		final ByteArrayOutputStream copy = new ByteArrayOutputStream();
		final TeeInputStream tee = tee(content, copy, content.length);

		assertThat(byteByByte ? readByteByByte(tee) : tee.readAllBytes(), equalTo(content));
		assertThat(copy.toByteArray(), equalTo(content));
		assertThat(tee.isComplete(), equalTo(true));
	}

	@Property
	public void givesUpTheCopyBeyondItsLimit(@ForAll @Size(min = 1) byte[] content,
		@ForAll @DoubleRange(max = 1, maxIncluded = false) double share) throws Exception {

		final TeeInputStream tee = tee(content, OutputStream.nullOutputStream(), (long) (share * content.length));

		assertThat(tee.readAllBytes(), equalTo(content));
		assertThat(tee.isComplete(), equalTo(false));
	}

	@Property
	public void passesEverythingOnWhenTheCopyFails(@ForAll @Size(min = 1) byte[] content) throws Exception {
		final TeeInputStream tee = tee(content, new OutputStream() {
			@Override
			public void write(final int value) throws IOException {
				throw new IOException("Disk full");
			}
		}, content.length);

		assertThat(tee.readAllBytes(), equalTo(content));
		assertThat(tee.isComplete(), equalTo(false));
	}

	@Property
	public void isIncompleteUntilTheEnd(@ForAll byte[] content) throws Exception {
		final TeeInputStream tee = tee(content, OutputStream.nullOutputStream(), content.length);

		for (int i = 0; i < content.length; i++) {
			tee.read();
		}

		assertThat(tee.isComplete(), equalTo(false));
	}

	@Property
	public void isIncompleteOnceSomethingWasSkipped(@ForAll byte[] content, @ForAll @IntRange(min = 1) int count)
		throws Exception {

		final TeeInputStream tee = tee(content, OutputStream.nullOutputStream(), content.length);

		tee.skip(count);
		tee.readAllBytes();
		assertThat(tee.isComplete(), equalTo(false));
	}
}
