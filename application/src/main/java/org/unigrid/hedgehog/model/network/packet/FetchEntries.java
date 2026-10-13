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

import java.util.List;
import java.util.Objects;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

@Data
@EqualsAndHashCode(callSuper = false)
public class FetchEntries extends Packet {
	public record Range(WriterId writer, long fromSeq, long toSeq) {
		public Range {
			Objects.requireNonNull(writer, "writer");

			if (fromSeq < 0 || toSeq < fromSeq) {
				throw new IllegalArgumentException("Invalid sequence range " + fromSeq + ".." + toSeq);
			}
		}
	}

	private ChainId chainId;
	private List<Range> ranges;

	public FetchEntries() {
		setType(Type.FETCH_ENTRIES);
	}

	@Builder
	public FetchEntries(final ChainId chainId, final List<Range> ranges) {
		this();
		this.chainId = chainId;
		this.ranges = Objects.requireNonNullElse(ranges, List.of());
	}
}
