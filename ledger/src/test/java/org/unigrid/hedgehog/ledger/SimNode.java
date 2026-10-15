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

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.*;

/* A process of the network and what the simulator keeps of it: the events it has had, whether it is down and the
   blocks it has taken in. The process is what answers the events; the app, engine and persisted state below are those
   of a bare engine and are null when the node is any other process. */
final class SimNode {
	final int index;
	final byte[] seed;
	final SimProcess process;
	final FakeApp app;
	final Timeouts timeouts;
	final List<Block> decided = new ArrayList<>();
	Consensus engine;
	/* What the node has on disk: the sign state of its engine after the last event, in the bytes the file would hold */
	SignState persisted = SignState.empty(1);
	int received;
	int armed;
	int fired;
	int incarnation;
	int sentUnsaved;
	boolean crashed;
	/* The next event whose actions this accepts is lost, once; null when none is awaited */
	Predicate<List<Action>> loses;
	/* The actions of the last event that was lost after its sign state was written */
	List<Action> lost = List.of();

	SimNode(int index, ValidatorSet validators, int goal, Timeouts timeouts, LongSupplier clock) {
		this.index = index;
		this.seed = LedgerFixtures.seed(index + 1);
		this.app = new FakeApp(validators, Ed25519.publicKey(seed), goal);
		this.timeouts = timeouts;
		this.engine = new Consensus(app, CHAIN, seed, timeouts);
		this.process = new EngineProcess(this, validators, clock);
	}

	SimNode(int index, SimProcess process) {
		this.index = index;
		this.seed = LedgerFixtures.seed(index + 1);
		this.process = process;
		this.app = null;
		this.timeouts = null;
	}
}
