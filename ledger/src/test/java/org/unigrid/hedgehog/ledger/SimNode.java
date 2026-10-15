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
import static org.unigrid.hedgehog.ledger.ConsensusFixtures.*;

final class SimNode {
	final int index;
	final byte[] seed;
	final FakeApp app;
	final Consensus engine;
	final List<Block> decided = new ArrayList<>();
	int received;
	int armed;
	int fired;
	int incarnation;
	boolean crashed;

	SimNode(int index, ValidatorSet validators, int goal, Timeouts timeouts) {
		this.index = index;
		this.seed = LedgerFixtures.seed(index + 1);
		this.app = new FakeApp(validators, Ed25519.publicKey(seed), goal);
		this.engine = new Consensus(app, CHAIN, seed, timeouts);
	}
}
