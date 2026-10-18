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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.unigrid.hedgehog.s3.spi.BlobStore;
import org.unigrid.hedgehog.s3.spi.BlobStoreException;

/* Writes slots that arrive in key order as a run blob, through a scratch file so a run of any size streams into
   the blob store. The layout, where every number is LEB128 and every byte string carries its length up front:

     run   := page... index
     page  := count, entry..., crc
     entry := shared, rest, value
     index := format, page count, (offset, length, first key) per page, bloom filter, crc

   A key keeps only the bytes past the prefix it shares with the key before it in the same page, so each page
   decodes on its own. A tombstone has no value, and crc is the CRC32C of what precedes it in the page or index as
   a big-endian int. A page closes once it passes the configured size and the manifest records where the index
   starts, so a reader fetches the index and then just the pages it needs. */
final class RunWriter {
	private final OutputStream output;
	private final Bloom bloom;
	private final int pageBytes;
	private final ByteArrayOutputStream page = new ByteArrayOutputStream();
	private final DataOutputStream entryOutput = new DataOutputStream(page);
	private final List<byte[]> firsts = new ArrayList<>();
	private final List<Long> offsets = new ArrayList<>();
	private final List<Integer> lengths = new ArrayList<>();
	private byte[] first;
	private byte[] last;
	private byte[] previous;
	private int count;
	private long offset;
	private long entries;
	private long size;

	private RunWriter(final OutputStream output, final Bloom bloom, final int pageBytes) {
		this.output = output;
		this.bloom = bloom;
		this.pageBytes = pageBytes;
	}

	/* Writes the slots, leaving tombstones out unless asked to keep them, and stores the run unless it is empty */
	static Optional<RunInfo> write(final Cursor slots, final long expected, final boolean tombstones,
		final Path directory, final BlobStore blobs, final NamespaceConfig config)
		throws IOException, BlobStoreException {

		final Path scratch = Files.createTempFile(directory, "run", ".tmp", LocalFiles.ownerOnly(directory));

		try {
			final RunWriter writer;

			try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(scratch))) {
				writer = new RunWriter(output, Bloom.forKeys(expected, config.getBloomBitsPerKey()),
					config.getPageBytes());

				writer.addAll(slots, tombstones);
				writer.finish();
			}

			return writer.entries == 0 ? Optional.empty() : Optional.of(writer.store(scratch, blobs));
		} finally {
			Files.deleteIfExists(scratch);
		}
	}

	private void addAll(final Cursor slots, final boolean tombstones) throws IOException, BlobStoreException {
		for (Slot slot = slots.next(); Objects.nonNull(slot); slot = slots.next()) {
			if (tombstones || !slot.tombstone()) {
				add(slot);
			}
		}
	}

	private void add(final Slot slot) throws IOException {
		final byte[] key = slot.key();
		final int shared = Objects.isNull(previous) ? 0 : Arrays.mismatch(previous, key);

		if (count == 0) {
			firsts.add(key);
		}

		Codec.writeNumber(entryOutput, shared);
		Codec.writeBytes(entryOutput, Arrays.copyOfRange(key, shared, key.length));
		Codec.writeNullable(entryOutput, slot.value(), Codec::writeBytes);
		bloom.add(key);
		first = Objects.requireNonNullElse(first, key);
		last = key;
		previous = key;
		count++;
		entries++;

		if (page.size() >= pageBytes) {
			closePage();
		}
	}

	private void closePage() throws IOException {
		final byte[] sealed = Codec.seal(Codec.encode(pageOutput -> {
			Codec.writeNumber(pageOutput, count);
			pageOutput.write(page.toByteArray());
		}));

		output.write(sealed);
		offsets.add(offset);
		lengths.add(sealed.length);
		offset += sealed.length;
		page.reset();
		previous = null;
		count = 0;
	}

	private void finish() throws IOException {
		if (count > 0) {
			closePage();
		}

		final RunIndex index = new RunIndex(firsts.toArray(byte[][]::new),
			offsets.stream().mapToLong(Long::longValue).toArray(), lengths.stream().mapToInt(Integer::intValue)
			.toArray(), bloom);
		final byte[] sealed = Codec.seal(Codec.encode(index::write));

		output.write(sealed);
		size = offset + sealed.length;
	}

	private RunInfo store(final Path scratch, final BlobStore blobs) throws IOException, BlobStoreException {
		try (InputStream input = Files.newInputStream(scratch)) {
			return new RunInfo(blobs.store(input), size, offset, entries, first, last);
		}
	}
}
