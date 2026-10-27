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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
* How a node takes a co-signed spork, whether a peer sent it or a board member handed it over REST: it is turned down
* when it is stamped further ahead than clocks drift apart, or when it cannot replace the stored spork, and otherwise
* stored in its place with the proposals it outdates dropped. The checks that decide are paid for by the turn of the
* source ({@link SporkLag.Turn}), which is free for what is handed to the node over REST.
*/
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SporkIntake {
	public enum Outcome {
		STORED, AHEAD, NOT_NEWER
	}

	/**
	* Judges the spork against {@code stored}, the spork the node held when it came in, and stores it if it may
	* replace that one. What a spork that passed its checks cost is given back to its source before it is stored, as
	* storing it, and dropping the proposals it outdates, is work of the node that no source pays for.
	*/
	public static Outcome take(GridSpork spork, GridSpork stored, SporkDatabase database,
		PendingSporks pendingSporks, SporkLag.Turn turn) {

		if (pendingSporks.isAheadOfTime(spork)) {
			log.atDebug().log("Dropped a {} spork stamped ahead of the clock", spork.getType());
			return Outcome.AHEAD;
		}

		if (!turn.charging(() -> mayReplace(spork, stored, database, turn))) {
			log.atDebug().log("Dropped a {} spork that cannot replace the stored one", spork.getType());
			return Outcome.NOT_NEWER;
		}

		turn.giveBack();
		return commit(spork, stored, database, pendingSporks) ? Outcome.STORED : Outcome.NOT_NEWER;
	}

	private static boolean mayReplace(GridSpork spork, GridSpork stored, SporkDatabase database, SporkLag.Turn turn) {
		return spork instanceof BoardKeys boardKeys ? BoardKeysAuthority.mayAccept(boardKeys, database, turn)
			: spork.canReplace(stored, turn);
	}

	private static boolean commit(GridSpork spork, GridSpork judged, SporkDatabase database,
		PendingSporks pendingSporks) {

		if (spork instanceof BoardKeys boardKeys) {
			return BoardKeysAuthority.commit(boardKeys, database, pendingSporks);
		}

		if (!database.replace(judged, spork)) {
			log.atDebug().log("Dropped a {} spork that a version stored meanwhile outdates", spork.getType());
			return false;
		}

		pendingSporks.retainProposalsOver(spork.getType(), () -> database.get(spork.getType()));
		return true;
	}
}
