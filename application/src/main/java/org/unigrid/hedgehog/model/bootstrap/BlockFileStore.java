/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Getter;

/*
   The legacy daemon preallocates every block file, so the tail of a file is zero padding rather than
   another record. A run of records therefore ends at the first byte pair that is not the network magic.
*/
public final class BlockFileStore {
	public static final int MAGIC = 0xe9fdc490;
	public static final String FILE_GLOB = "blk*.dat";

	private static final int RECORD_PREFIX_SIZE = 8;

	private final List<ByteBuffer> files;
	@Getter private final List<Path> paths;

	private BlockFileStore(List<ByteBuffer> files, List<Path> paths) {
		this.files = files;
		this.paths = paths;
	}

	public static BlockFileStore open(Path directory) throws IOException {
		final List<Path> paths = sortedBlockFiles(directory);
		final List<ByteBuffer> files = new ArrayList<>(paths.size());

		for (final Path path : paths) {
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
				files.add(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
					.order(ByteOrder.LITTLE_ENDIAN));
			}
		}

		if (files.isEmpty()) {
			throw new IOException("No " + FILE_GLOB + " files found in " + directory);
		}

		return new BlockFileStore(files, paths);
	}

	public void forEachBlock(BlockConsumer consumer) {
		for (int file = 0; file < files.size(); file++) {
			scanFile(file, consumer);
		}
	}

	public ByteBuffer read(BlockLocation location) {
		return files.get(location.getFile()).duplicate().order(ByteOrder.LITTLE_ENDIAN)
			.position(location.getOffset()).limit(location.getOffset() + location.getLength()).slice()
			.order(ByteOrder.LITTLE_ENDIAN);
	}

	private void scanFile(int file, BlockConsumer consumer) {
		final ByteBuffer contents = files.get(file);
		int position = 0;

		while (position + RECORD_PREFIX_SIZE < contents.limit()) {
			if (contents.getInt(position) != MAGIC) {
				return;
			}

			final int length = contents.getInt(position + Integer.BYTES);
			final int offset = position + RECORD_PREFIX_SIZE;

			if (length <= 0 || offset + length > contents.limit()) {
				return;
			}

			consumer.accept(new BlockLocation(file, offset, length));
			position = offset + length;
		}
	}

	private static List<Path> sortedBlockFiles(Path directory) throws IOException {
		final List<Path> paths = new ArrayList<>();

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, FILE_GLOB)) {
			stream.forEach(paths::add);
		}

		Collections.sort(paths);
		return paths;
	}

	@FunctionalInterface
	public interface BlockConsumer {
		void accept(BlockLocation location);
	}
}
