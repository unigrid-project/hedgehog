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

import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.unigrid.hedgehog.model.network.Packet;
import org.unigrid.hedgehog.proxy.chain.Admission;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.Entry;
import org.unigrid.hedgehog.proxy.chain.WriterId;

@Data
@EqualsAndHashCode(callSuper = false)
public class EntryRefused extends Packet {
	private ChainId chainId;
	private WriterId writer;
	private long seq;
	private Admission admission;

	public EntryRefused() {
		setType(Type.ENTRY_REFUSED);
	}

	@Builder
	public EntryRefused(final ChainId chainId, final WriterId writer, final long seq, final Admission admission) {
		this();

		if (seq < 0) {
			throw new IllegalArgumentException("A sequence number is never negative, found " + seq);
		}

		this.chainId = chainId;
		this.writer = writer;
		this.seq = seq;
		this.admission = admission;
	}

	public static EntryRefused of(final Entry entry, final Admission admission) {
		return new EntryRefused(entry.header().chainId(), entry.header().writer(), entry.header().seq(), admission);
	}
}
