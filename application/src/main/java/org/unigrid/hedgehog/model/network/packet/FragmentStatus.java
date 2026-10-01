/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Value;
import org.unigrid.hedgehog.model.storage.DeleteProof;
import org.unigrid.hedgehog.model.storage.GroupId;

@Data
@EqualsAndHashCode(callSuper = false)
public class FragmentStatus extends Packet implements Correlated, Serializable {
	public enum State {
		NONE, HELD, TOMBSTONE;

		/* A state byte from a peer is untrusted; one this node does not know must never count as a held copy */
		public static State of(final int ordinal) {
			final State[] states = values();
			return ordinal >= 0 && ordinal < states.length ? states[ordinal] : NONE;
		}
	}

	@Value
	@AllArgsConstructor
	public static class Entry implements Serializable {
		private final GroupId groupId;
		private final State state;
		private final int index;

		/* Present for a tombstone, so a peer can check the delete for itself instead of trusting the sender */
		@Getter(AccessLevel.NONE) private final DeleteProof proof;

		public Entry(final GroupId groupId, final State state, final int index) {
			this(groupId, state, index, null);
		}

		public Optional<DeleteProof> getProof() {
			return Optional.ofNullable(proof);
		}
	}

	private long requestId;
	private List<Entry> entries;

	public FragmentStatus() {
		setType(Type.FRAGMENT_STATUS);
	}

	@Builder
	public FragmentStatus(final long requestId, final List<Entry> entries) {
		this();
		this.requestId = requestId;
		this.entries = entries;
	}
}
