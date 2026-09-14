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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.SneakyThrows;
import net.jqwik.api.Example;

public class SnapshotReaderTest {
	@Example
	@SneakyThrows
	public void shouldRejectACorruptedMagic() {
		final Path path = validSnapshot();
		final byte[] bytes = Files.readAllBytes(path);

		bytes[0] ^= 0x01;
		Files.write(path, bytes);

		try {
			SnapshotReader.open(path);
			throw new AssertionError("A snapshot with a corrupted magic was accepted");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), equalTo("Not a legacy chain snapshot"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldRejectAWrongVersion() {
		final Path path = validSnapshot();
		final byte[] bytes = Files.readAllBytes(path);

		ByteBuffer.wrap(bytes).putInt(SnapshotFormat.VERSION_OFFSET, 1);
		Files.write(path, bytes);

		try {
			SnapshotReader.open(path);
			throw new AssertionError("A snapshot with version 1 was accepted");

		} catch (IOException expected) {
			assertThat(expected.getMessage(), equalTo("Snapshot is format version 1, this build reads "
				+ SnapshotFormat.VERSION + "; re-import the bootstrap"));
		}
	}

	@SneakyThrows
	private static Path validSnapshot() {
		final Path path = Files.createTempFile("hhg-snapshot-", ".dat");

		path.toFile().deleteOnExit();
		SnapshotBuilder.build(BlockFixture.directory(), path);
		return path;
	}
}
