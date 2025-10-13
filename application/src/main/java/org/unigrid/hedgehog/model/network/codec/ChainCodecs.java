/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

	private ChainCodecs() {
		/* Static helpers only */
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
		entries.forEach(entry -> StorageCodecs.writeBytes(out, EntryCodec.encode(entry)));
	}

	public static List<Entry> readEntries(final ByteBuf in) {
		return IntStream.range(0, readCount(in, MAX_ENTRIES_PER_PACKET)).mapToObj(i -> readEntry(in))
			.collect(Collectors.toList());
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
