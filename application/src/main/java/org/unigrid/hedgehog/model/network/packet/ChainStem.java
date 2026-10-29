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
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.Entry;

/* Entries passed on to one member before any member floods them, laid out as a CHAIN_ENTRY is */
@Data
@EqualsAndHashCode(callSuper = false)
public class ChainStem extends Packet {
	private List<Entry> entries;

	public ChainStem() {
		setType(Type.CHAIN_STEM);
	}

	@Builder
	public ChainStem(final List<Entry> entries) {
		this();
		this.entries = Objects.requireNonNullElse(entries, List.of());
	}
}
