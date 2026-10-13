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

package org.unigrid.hedgehog.model.network.packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.model.network.codec.ChainCodecs;
import org.unigrid.hedgehog.proxy.chain.Entry;

@Data
@EqualsAndHashCode(callSuper = false)
public class ChainEntry extends Packet {
	private List<Entry> entries;

	public ChainEntry() {
		setType(Type.CHAIN_ENTRY);
	}

	@Builder
	public ChainEntry(final List<Entry> entries) {
		this();
		this.entries = Objects.requireNonNullElse(entries, List.of());
	}

	/* In order, at most MAX_ENTRIES_PER_PACKET to a packet and never more bytes than a frame may take */
	public static List<ChainEntry> batches(final List<Entry> entries) {
		final List<ChainEntry> batches = new ArrayList<>();
		List<Entry> batch = new ArrayList<>();
		long bytes = ChainCodecs.ENTRIES_FRAME_OVERHEAD;

		for (final Entry entry : entries) {
			if (!batch.isEmpty() && !fits(batch.size(), bytes, entry)) {
				batches.add(new ChainEntry(List.copyOf(batch)));
				batch = new ArrayList<>();
				bytes = ChainCodecs.ENTRIES_FRAME_OVERHEAD;
			}

			batch.add(entry);
			bytes += ChainCodecs.encodedBytes(entry);
		}

		if (!batch.isEmpty()) {
			batches.add(new ChainEntry(List.copyOf(batch)));
		}

		return batches;
	}

	private static boolean fits(final int count, final long bytes, final Entry entry) {
		return count < ChainCodecs.MAX_ENTRIES_PER_PACKET
			&& bytes + ChainCodecs.encodedBytes(entry) <= ChainCodecs.MAX_FRAME_BYTES;
	}
}
