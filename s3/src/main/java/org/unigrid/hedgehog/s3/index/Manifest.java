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

package org.unigrid.hedgehog.s3.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.unigrid.hedgehog.s3.spi.BlobId;

/* What the root names: the sequence every stored write is at or below, the runs newest first and the superseded
   blobs that wait out their grace period before they are deleted. The blob holds a magic number, the format byte
   and those fields, sealed with a CRC32C. */
record Manifest(long sequence, List<RunInfo> runs, List<Pending> pending) {
	static final Manifest EMPTY = new Manifest(0, List.of(), List.of());

	private static final int MAGIC = 0x48484E53;
	private static final int FORMAT = 1;

	record Pending(BlobId blob, Instant due) {
	}

	Manifest {
		runs = List.copyOf(runs);
		pending = List.copyOf(pending);
	}

	byte[] encode() {
		return Codec.seal(Codec.encode(output -> {
			output.writeInt(MAGIC);
			output.writeByte(FORMAT);
			output.writeLong(sequence);
			Codec.writeList(output, runs, Manifest::writeRun);
			Codec.writeList(output, pending, Manifest::writePending);
		}));
	}

	static Manifest decode(final byte[] bytes) throws IOException {
		return Codec.decode(Codec.unseal(bytes), input -> {
			final int magic = input.readInt();
			final int format = input.readUnsignedByte();

			if (magic != MAGIC || format != FORMAT) {
				throw new CorruptIndexException("Not a namespace manifest of a known format");
			}

			return new Manifest(input.readLong(), Codec.readList(input, Manifest::readRun),
				Codec.readList(input, Manifest::readPending));
		});
	}

	private static void writeRun(final DataOutput output, final RunInfo run) throws IOException {
		Codec.writeString(output, run.id().value());
		Codec.writeNumber(output, run.size());
		Codec.writeNumber(output, run.indexOffset());
		Codec.writeNumber(output, run.entries());
		Codec.writeBytes(output, run.first());
		Codec.writeBytes(output, run.last());
	}

	private static RunInfo readRun(final DataInput input) throws IOException {
		return new RunInfo(new BlobId(Codec.readString(input)), Codec.readNumber(input), Codec.readNumber(input),
			Codec.readNumber(input), Codec.readBytes(input), Codec.readBytes(input));
	}

	private static void writePending(final DataOutput output, final Pending pending) throws IOException {
		Codec.writeString(output, pending.blob().value());
		Codec.writeInstant(output, pending.due());
	}

	private static Pending readPending(final DataInput input) throws IOException {
		return new Pending(new BlobId(Codec.readString(input)), Codec.readInstant(input));
	}
}
