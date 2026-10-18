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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/* The in-memory file system cannot map, so this covers the positional reads that stand in for mapping */
public class CacheFileTest {
	@Property(tries = 200)
	public void readsTheRangeAskedForWhereTheFileSystemCannotMap(@ForAll @IntRange(max = 300_000) int size,
		@ForAll long seed, @ForAll @DoubleRange(max = 1) double start, @ForAll @DoubleRange(max = 1) double share)
		throws Exception {

		final byte[] content = new byte[size];
		final int offset = (int) (start * size);
		final int length = (int) (share * (size - offset));
		final ByteArrayOutputStream output = new ByteArrayOutputStream();

		new Random(seed).nextBytes(content);

		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Path path = Files.write(fileSystem.getPath("/cached"), content);

			try (CacheFile file = CacheFile.open(path)) {
				file.writeTo(output, offset, length);
			}
		}

		assertThat(output.toByteArray(), equalTo(Arrays.copyOfRange(content, offset, offset + length)));
	}
}
