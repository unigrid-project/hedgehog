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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.AccessLevel;
import lombok.Cleanup;
import lombok.NoArgsConstructor;

/*
   Checks a snapshot without memory-mapping it. SnapshotReader maps five regions and there is no
   portable way to release a mapping on demand, so a file it has opened cannot be renamed or deleted
   on Windows until a garbage collection happens to unmap it. Every caller that validates a file it
   is about to move, truncate or append to therefore comes here instead: the download before it
   installs, the builder before it reports, and the sign command before it rewrites the trailing
   block.
*/
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SnapshotInspector {
	/*
	   The format is checked first, so a file that is not a snapshot fails on that rather than on a
	   content length derived from a header nobody has validated. The signature state is returned
	   rather than thrown on, because only the caller knows what it was about to do with the file and
	   can say so: the temporary name this sees is not a name the user would recognise.
	*/
	public static SignatureStatus validate(Path snapshot) throws IOException {
		readHeader(snapshot);
		return SnapshotSignature.read(snapshot).getStatus();
	}

	public static SnapshotInfo summarise(Path snapshot) throws IOException {
		return readHeader(snapshot).info().build();
	}

	public static long totalBalance(Path snapshot) throws IOException {
		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);
		final SnapshotHeader header = SnapshotHeader.read(channel, snapshot);
		final ByteBuffer record = ByteBuffer.allocate(SnapshotFormat.ADDRESS_RECORD_SIZE)
			.order(ByteOrder.BIG_ENDIAN);
		long total = 0;

		channel.position(header.addressTableOffset());

		for (int address = header.addressCount(); address > 0; address--) {
			record.clear();

			if (channel.read(record) != SnapshotFormat.ADDRESS_RECORD_SIZE) {
				throw new IOException("Snapshot ends inside its address table");
			}

			total += record.getLong(SnapshotFormat.ADDRESS_BALANCE_OFFSET);
		}

		return total;
	}

	private static SnapshotHeader readHeader(Path snapshot) throws IOException {
		@Cleanup final FileChannel channel = FileChannel.open(snapshot, StandardOpenOption.READ);

		return SnapshotHeader.read(channel, snapshot).verifyFormat();
	}
}
