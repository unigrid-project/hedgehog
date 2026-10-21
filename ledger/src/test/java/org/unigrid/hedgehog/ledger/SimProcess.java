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

import java.util.List;

/* What the simulator drives: one process of the network, a bare engine over a FakeApp or a ledger node. It is given
   the events of its world and answers each with the actions it asks for. What it signed in an event is on its disk by
   the time the actions are returned, so the simulator may perform them at once, and a restart from the disk finds it. */
interface SimProcess {
	/* Whether the process has begun a height since it was made; one that was down from the start has not */
	boolean started();

	List<Action> start();

	/* The process is gone and a new one begins with the files of the old one, and nothing else */
	List<Action> startFromDisk();

	List<Action> onMessage(Message message);

	List<Action> onTimeout(Timeout timeout);

	/* There may be something to propose now */
	List<Action> onWork();

	/* About once a second: whatever the process does with the time, and what it says again for peers that lost it */
	List<Action> tick();

	/* The simulator performs the action that hands over the block that was decided */
	List<Action> decided(Block block);

	/* The blocks the process has taken in, in order, which are what it serves to a peer that is behind */
	List<Block> committed();

	/* Whether the message, which this process signed, is on its disk, or needs no keeping as its height is over */
	boolean saved(Message signed);

	AccountKey self();
}
