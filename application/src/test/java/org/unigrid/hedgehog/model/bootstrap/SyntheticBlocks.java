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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/*
   Writes block files that have the same framing and header layout as the legacy daemon's, so branches
   and reorganisations can be set up deliberately rather than hunted for in the real chain. The blocks
   carry no transactions, which is all the chain linker ever looks at.
*/
public final class SyntheticBlocks {
	public static final int BASE_TIME = 1536062400;
	public static final int BLOCK_INTERVAL = 60;

	private SyntheticBlocks() {
		/* Empty on purpose */
	}

	public static int timeAt(int height) {
		return BASE_TIME + height * BLOCK_INTERVAL;
	}

	public static BlockFileStore store(Consumer<Builder> definition) throws IOException {
		final Builder builder = new Builder();

		definition.accept(builder);
		return BlockFileStore.open(builder.write());
	}

	public static final class Builder {
		private static final int MERKLE_OFFSET = 36;
		private static final int TIME_OFFSET = 68;
		private static final int VERSION_WITH_ACCUMULATOR = 4;

		private final ByteArrayOutputStream records = new ByteArrayOutputStream();
		private final List<byte[]> mainChain = new ArrayList<>();
		private int nonce;

		public void mainChain(int blocksAfterGenesis) {
			mainChain.add(append(1, new byte[Hashing.HASH_SIZE], timeAt(0)));

			for (int height = 1; height <= blocksAfterGenesis; height++) {
				mainChain.add(append(VERSION_WITH_ACCUMULATOR, mainChain.get(height - 1), timeAt(height)));
			}
		}

		public void fork(int fromHeight, int length) {
			byte[] previous = mainChain.get(fromHeight);

			for (int i = 1; i <= length; i++) {
				previous = append(VERSION_WITH_ACCUMULATOR, previous, timeAt(fromHeight + i));
			}
		}

		private byte[] append(int version, byte[] previousHash, int time) {
			final byte[] header = header(version, previousHash, time);
			final ByteBuffer record = ByteBuffer.allocate(header.length + 9).order(ByteOrder.LITTLE_ENDIAN);

			record.putInt(BlockFileStore.MAGIC).putInt(header.length + 1).put(header).put((byte) 0);
			records.write(record.array(), 0, record.position());

			return version < VERSION_WITH_ACCUMULATOR
				? BlockParser.GENESIS_HASH : Hashing.doubleSha256(ByteBuffer.wrap(header));
		}

		private byte[] header(int version, byte[] previousHash, int time) {
			final ByteBuffer header = ByteBuffer.allocate(BlockParser.headerSize(version))
				.order(ByteOrder.LITTLE_ENDIAN);

			header.putInt(version).put(previousHash);
			header.putInt(MERKLE_OFFSET, ++nonce);
			header.putInt(TIME_OFFSET, time);
			header.putInt(TIME_OFFSET + Integer.BYTES, 0x1e0fffff);
			header.putInt(TIME_OFFSET + 2 * Integer.BYTES, nonce);
			return header.array();
		}

		private Path write() throws IOException {
			final Path directory = Files.createTempDirectory("hhg-synthetic-");

			Files.write(directory.resolve("blk00000.dat"), records.toByteArray());
			directory.toFile().deleteOnExit();
			return directory;
		}
	}
}
