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

package org.unigrid.hedgehog.ledger;

import java.util.HashSet;
import java.util.List;

/* The validators of a round, in the order that decides who proposes. The set is fixed for the whole round;
   it changes only when a round ends. */
public record ValidatorSet(List<AccountKey> keys) {
	public static final int MAX_SIZE = 1000;

	public ValidatorSet {
		keys = List.copyOf(keys);

		if (keys.isEmpty() || keys.size() > MAX_SIZE) {
			throw new IllegalArgumentException("A validator set holds 1 to " + MAX_SIZE + " keys: " + keys.size());
		}

		if (new HashSet<>(keys).size() != keys.size()) {
			throw new IllegalArgumentException("A validator set holds each key once");
		}
	}

	public int size() {
		return keys.size();
	}

	public boolean contains(AccountKey key) {
		return keys.contains(key);
	}

	/* Round-robin over the set, starting with the first key at height 1 */
	public AccountKey proposerAt(long height) {
		if (height < 1) {
			throw new IllegalArgumentException("Heights start at 1, found " + height);
		}

		return keys.get((int) ((height - 1) % keys.size()));
	}

	/* The smallest number of validators that is more than two thirds of the set */
	public int quorum() {
		return keys.size() * 2 / 3 + 1;
	}
}
