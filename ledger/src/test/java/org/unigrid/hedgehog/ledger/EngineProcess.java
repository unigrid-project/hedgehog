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

/* The bare engine of a SimNode over the FakeApp of the node. Its disk is the chain of the app and the sign state of the
   engine in the bytes the file would hold, which is written after every event, before the actions are returned. */
final class EngineProcess implements SimProcess {
	private final SimNode node;
	private final ValidatorSet validators;
	private final LongSupplier clock;

	EngineProcess(SimNode node, ValidatorSet validators, LongSupplier clock) {
		this.node = node;
		this.validators = validators;
		this.clock = clock;
	}

	@Override
	public boolean started() {
		return node.engine.height() != 0;
	}

	@Override
	public List<Action> start() {
		return kept(node.engine.start(node.app.nextHeight(), validators, clock.getAsLong()));
	}

	@Override
	public List<Action> startFromDisk() {
		node.engine = new Consensus(node.app, ConsensusFixtures.CHAIN, node.seed, node.timeouts);
		return kept(node.engine.restore(node.persisted, validators, clock.getAsLong()));
	}

	@Override
	public List<Action> onMessage(Message message) {
		return kept(node.engine.onMessage(message, clock.getAsLong()));
	}

	@Override
	public List<Action> onTimeout(Timeout timeout) {
		return kept(node.engine.onTimeout(timeout, clock.getAsLong()));
	}

	@Override
	public List<Action> onWork() {
		return kept(node.engine.onWork(clock.getAsLong()));
	}

	/* As LedgerNode ticks: what the engine says again, and then whatever the time brings, such as a node at rest that
	   a validator alone may wake again */
	@Override
	public List<Action> tick() {
		final long at = clock.getAsLong();
		final List<Action> actions = new ArrayList<>(node.engine.resume(at));

		actions.addAll(node.engine.onWork(at));
		return kept(actions);
	}

	@Override
	public List<Action> decided(Block block) {
		node.app.commit(block);
		return start();
	}

	@Override
	public List<Block> committed() {
		return List.copyOf(node.app.chain);
	}

	@Override
	public boolean saved(Message signed) {
		return node.persisted.ballots().contains(signed) || node.persisted.proposals().contains(signed);
	}

	@Override
	public AccountKey self() {
		return node.app.self;
	}

	private List<Action> kept(List<Action> actions) {
		node.persisted = SignState.decode(node.engine.snapshot().encode(), validators.size());
		return actions;
	}
}
