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

package org.unigrid.hedgehog.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.unigrid.hedgehog.model.s3.entity.UploadTooLargeException;

public class LimitedInputStreamTest {
	private static final int MAX_SIZE = 4096;

	@Property
	public void shouldPassDataWithinLimit(@ForAll @Size(max = MAX_SIZE) byte[] data,
		@ForAll @IntRange(max = MAX_SIZE) int slack) throws IOException {

		try (InputStream stream = new LimitedInputStream(new ByteArrayInputStream(data), data.length + slack)) {
			assertThat(stream.readAllBytes(), equalTo(data));
		}
	}

	@Property
	public void shouldRefuseDataOverLimit(@ForAll @Size(min = 1, max = MAX_SIZE) byte[] data,
		@ForAll @IntRange(min = 1, max = MAX_SIZE) int excess) {

		Throwable thrown = null;

		try (InputStream stream = new LimitedInputStream(new ByteArrayInputStream(data),
			Math.max(0, data.length - excess))) {

			stream.readAllBytes();
		} catch (IOException ex) {
			thrown = ex;
		}

		assertThat(thrown, isA(UploadTooLargeException.class));
	}

	@Property
	public void shouldRefuseSingleBytesOverLimit(@ForAll @Size(min = 1, max = 64) byte[] data) {
		Throwable thrown = null;

		try (InputStream stream = new LimitedInputStream(new ByteArrayInputStream(data), data.length - 1)) {
			while (stream.read() != -1) {
				continue;
			}
		} catch (IOException ex) {
			thrown = ex;
		}

		assertThat(thrown, isA(UploadTooLargeException.class));
	}
}
