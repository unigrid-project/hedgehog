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

package org.unigrid.hedgehog.ledger;

/* What the engine wants done: send a message to every validator, call it back after a delay with a timeout, or
   hand over the block that was decided (with its certificate) to be committed */
public sealed interface Action permits Action.Broadcast, Action.Schedule, Action.Decide {
	record Broadcast(Message message) implements Action {
	}

	record Schedule(Timeout timeout, long delayMillis) implements Action {
	}

	record Decide(Block block) implements Action {
	}
}
