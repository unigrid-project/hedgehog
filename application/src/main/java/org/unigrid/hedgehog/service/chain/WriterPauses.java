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

package org.unigrid.hedgehog.service.chain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.unigrid.hedgehog.model.network.packet.EntryRefused;
import org.unigrid.hedgehog.proxy.chain.ChainId;
import org.unigrid.hedgehog.proxy.chain.WriterId;

/* The writers of this node that a member asked to wait because a chain had no room for their entries. A writer
   that finds itself here stops reading its socket, offers everything from the refused sequence number again and
   resumes. A member can refuse any writer by name, so only the latest few are kept rather than whatever it
   sends. A pause that is repeated lowers the sequence number but keeps its place, so the one paused first goes
   first and repeating a refusal does not keep a pause alive. */
public final class WriterPauses {
	static final int MAX_PAUSES = 1024;

	private record Key(ChainId chainId, WriterId writer) {
		/* One writer on one chain */
	}

	private final Map<Key, Long> refusedFrom = Collections.synchronizedMap(new LinkedHashMap<>() {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<Key, Long> eldest) {
			return size() > MAX_PAUSES;
		}
	});

	public void pause(final EntryRefused refusal) {
		refusedFrom.merge(new Key(refusal.getChainId(), refusal.getWriter()), refusal.getSeq(), Math::min);
	}

	/* The lowest sequence number refused since the writer last resumed */
	public OptionalLong refusedFrom(final ChainId chainId, final WriterId writer) {
		final Long seq = refusedFrom.get(new Key(chainId, writer));

		return seq == null ? OptionalLong.empty() : OptionalLong.of(seq);
	}

	public boolean isPaused(final ChainId chainId, final WriterId writer) {
		return refusedFrom.containsKey(new Key(chainId, writer));
	}

	public void resume(final ChainId chainId, final WriterId writer) {
		refusedFrom.remove(new Key(chainId, writer));
	}
}
