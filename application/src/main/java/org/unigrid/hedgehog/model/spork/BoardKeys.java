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

package org.unigrid.hedgehog.model.spork;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;

/* The board keys that sign sporks, as a chain of changes from the keys built into the release (BoardKeyChain). An
   empty chain leaves the built-in keys active. */
@Data @ToString(callSuper = true)
@EqualsAndHashCode(callSuper = false)
public class BoardKeys extends GridSpork implements Serializable {
	private static final long serialVersionUID = -2858013573021957746L;

	public BoardKeys() {
		setType(Type.BOARD_KEYS);
		setFlags((short) (getFlags() | Flag.GOVERNED.getValue()));
		setData(new SporkData());
	}

	/* What the chain makes of the board keys, from the root of this node, or nothing when it does not hold */
	public Optional<ActiveKeys> chainKeys() {
		return Optional.ofNullable(this.<SporkData>getData()).map(SporkData::getChanges)
			.flatMap(BoardKeyChain::verified);
	}

	/* Two keys that the chain makes active sign it, so a change takes effect with a head that the new board signed,
	   whatever keys the node goes by until then */
	@Override
	protected Set<String> headKeys() {
		return chainKeys().map(ActiveKeys::active).orElse(Set.of());
	}

	@Override
	protected Set<String> logKeys() {
		return chainKeys().map(ActiveKeys::known).orElse(Set.of());
	}

	/* The keys that sign the board keys are those of their own chain, so a version of them is taken or nothing */
	@Override
	public boolean wasTakenByTheNetwork() {
		return isTakenByTheNetwork();
	}

	/**
	* A chain of board keys only grows: a version holds the chain of the stored one and adds to it, or holds the same
	* chain and is newer, as a renewal does. No version with another chain takes the place of the stored one, however
	* new it is, so neither a sibling nor a first version undoes a change. A change is known by what it decides
	* ({@link BoardKeyChain#digestOf}), as whoever holds it can drop an approval beyond the quorum or sign it again,
	* and the chain of this version is verified as a whole before its head is. Its log need not carry the stored one
	* on: the chain is the history that counts, and a version that board members signed on their own machines is a
	* first version.
	*/
	@Override
	protected boolean isSuccessorOf(GridSpork stored, SignatureLog storedLog) {
		return followsOn(stored);
	}

	/* As isSuccessorOf() decides, which costs no signature */
	public boolean followsOn(GridSpork stored) {
		return Objects.isNull(stored) || holdsTheChainOf(stored) && (lengthOf(this) > lengthOf(stored)
			|| isNewerThan(stored));
	}

	/* Whether the chain of this spork begins with the whole chain of the other */
	public boolean holdsTheChainOf(GridSpork other) {
		return BoardKeyChain.holds(this.<SporkData>getData().getChanges(), other.<SporkData>getData().getChanges());
	}

	private static int lengthOf(GridSpork spork) {
		return spork.<SporkData>getData().getChanges().size();
	}

	@Data
	public static class SporkData implements ChunkData {
		private static final long serialVersionUID = 6815529384750267513L;

		private List<KeyChange> changes = new ArrayList<>();

		@Override
		public SporkData empty() {
			return new SporkData();
		}
	}
}
