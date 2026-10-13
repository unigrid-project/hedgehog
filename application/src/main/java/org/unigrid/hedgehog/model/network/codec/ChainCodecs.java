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

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.EntryCodec;
import org.unigrid.hedgehog.proxy.chain.Head;
import org.unigrid.hedgehog.proxy.chain.InvalidEntryException;
import org.unigrid.hedgehog.proxy.chain.WriterId;

public final class ChainCodecs {
	/* The largest entry any communication spork allows; each chain applies its own, lower limit */
	public static final int MAX_ENTRY_BYTES = 1 << 20;
	public static final int MAX_ENTRIES_PER_PACKET = 64;
	public static final int MAX_HEADS_PER_PACKET = 4096;

	/* The most a frame of the stream chain traffic has to itself may take, header included. Any peer may open that
	   stream, so the limit is far below the 256 MiB of the main stream, and above what seven entries of the
	   largest size need, which is what ChainEntry#batches fills a frame with. */
	public static final int MAX_FRAME_BYTES = 8 << 20;

	/* Every frame starts with the magic number, the packet type and the payload size */
	public static final int FRAME_HEADER_BYTES = 2 * Short.BYTES + Integer.BYTES;

	/* What a frame of entries takes before its first entry: the frame header and the count */
	public static final int ENTRIES_FRAME_OVERHEAD = FRAME_HEADER_BYTES + Short.BYTES;

	private ChainCodecs() {
		/* Static helpers only */
	}

	/* What one entry takes in a frame of entries: its length and then its encoding */
	public static int encodedBytes(final Entry entry) {
		return Integer.BYTES + entry.size();
	}

	public static void writeCount(final ByteBuf out, final int count, final int max) {
		requireAtMost(count, max);
		out.writeShort(count);
	}

	public static int readCount(final ByteBuf in, final int max) {
		final int count = in.readUnsignedShort();

		requireAtMost(count, max);
		return count;
	}

	private static void requireAtMost(final int count, final int max) {
		if (count > max) {
			throw new IllegalArgumentException("At most " + max + " items fit in one packet, found " + count);
		}
	}

	public static void writeChainId(final ByteBuf out, final ChainId chainId) {
		out.writeBytes(chainId.bytes());
	}

	public static ChainId readChainId(final ByteBuf in) {
		return new ChainId(ByteBufUtil.getBytes(in.readSlice(ChainId.SIZE)));
	}

	public static void writeWriter(final ByteBuf out, final WriterId writer) {
		out.writeBytes(writer.bytes());
	}

	public static WriterId readWriter(final ByteBuf in) {
		return new WriterId(ByteBufUtil.getBytes(in.readSlice(WriterId.SIZE)));
	}

	public static void writeEntries(final ByteBuf out, final List<Entry> entries) {
		writeCount(out, entries.size(), MAX_ENTRIES_PER_PACKET);
		requireWithinFrame(ENTRIES_FRAME_OVERHEAD + entries.stream().mapToLong(ChainCodecs::encodedBytes).sum());
		entries.forEach(entry -> StorageCodecs.writeBytes(out, EntryCodec.encode(entry)));
	}

	/* The running size is checked after each entry, so a packet built to outgrow the frame limit holds at most one
	   entry more than the limit in memory before it is refused */
	public static List<Entry> readEntries(final ByteBuf in) {
		final int count = readCount(in, MAX_ENTRIES_PER_PACKET);
		final int start = in.readerIndex();
		final List<Entry> entries = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			entries.add(readEntry(in));
			requireWithinFrame(ENTRIES_FRAME_OVERHEAD + (long) (in.readerIndex() - start));
		}

		return entries;
	}

	private static void requireWithinFrame(final long frameBytes) {
		if (frameBytes > MAX_FRAME_BYTES) {
			throw new IllegalArgumentException("A frame holds " + MAX_FRAME_BYTES + " bytes, not " + frameBytes);
		}
	}

	/* The length is checked before the slice, so a forged one allocates nothing */
	private static Entry readEntry(final ByteBuf in) {
		final int length = in.readInt();

		if (length < 0 || length > MAX_ENTRY_BYTES) {
			throw new IllegalArgumentException("Invalid entry length " + length);
		}

		try {
			return EntryCodec.decode(ByteBufUtil.getBytes(in.readSlice(length)), MAX_ENTRY_BYTES);
		} catch (InvalidEntryException ex) {
			throw new IllegalArgumentException(ex.getMessage(), ex);
		}
	}

	public static void writeHeads(final ByteBuf out, final Map<WriterId, Head> heads) {
		writeCount(out, heads.size(), MAX_HEADS_PER_PACKET);

		heads.forEach((writer, head) -> {
			writeWriter(out, writer);
			out.writeLong(head.seq()).writeLong(head.hash());
		});
	}

	public static Map<WriterId, Head> readHeads(final ByteBuf in) {
		final int count = readCount(in, MAX_HEADS_PER_PACKET);
		final Map<WriterId, Head> heads = new HashMap<>();

		for (int i = 0; i < count; i++) {
			final WriterId writer = readWriter(in);
			final long seq = in.readLong();

			if (heads.put(writer, new Head(seq, in.readLong())) != null) {
				throw new IllegalArgumentException("Writer " + writer + " has more than one head");
			}
		}

		return heads;
	}
}
