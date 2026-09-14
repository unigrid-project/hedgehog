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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/*
   The first 31 blocks of the legacy chain, copied verbatim out of blk00000.dat so the parser is tested
   against bytes the legacy daemon actually wrote rather than against bytes this codebase invented.
*/
public final class BlockFixture {
	public static final String RESOURCE = "/bootstrap/blk00000.dat";
	public static final int BLOCK_COUNT = 31;

	private BlockFixture() {
		/* Empty on purpose */
	}

	public static Path directory() throws IOException {
		final Path directory = Files.createTempDirectory("hhg-bootstrap-");

		try (InputStream stream = BlockFixture.class.getResourceAsStream(RESOURCE)) {
			Files.copy(stream, directory.resolve("blk00000.dat"));
		}

		directory.toFile().deleteOnExit();
		return directory;
	}
}
