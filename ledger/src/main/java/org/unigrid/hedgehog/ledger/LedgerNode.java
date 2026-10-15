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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.unigrid.hedgehog.ledger.Action.Decide;

/* One validator's ledger, consensus engine and sign state tied together, or an observer's when there is no seed. Every
   event the engine is given ends the same way, before the driver sees any action of it: a block it decided is
   committed and the next height started, and then what the engine has signed is saved. The driver therefore sends
   nothing that is not on disk. A height that is decided and committed has no sign state kept: the node never signs at
   a height the ledger has passed, so the signatures the deciding event made are sent and not saved.

   Whatever fails reaches the caller as it is, and nothing is returned, so nothing leaves the node. A commit that
   fails leaves an engine that has decided a block the ledger has not taken in, and a save that fails leaves an engine
   ahead of its store. In both the node is not to carry on: the driver stops it and starts a new one over the same
   files, which restores the state last saved, and the decisions of its peers bring the block in. What the failed
   event signed was neither saved nor sent, so the restored node is free to sign differently for it.

   Events come one at a time, as the engine takes them, and the driver calls tick() about once a second, which is the
   rate the engine's rules for a node at rest are written for. */
public final class LedgerNode {
	private final Ledger ledger;
	private final Consensus engine;
	private final SignStore store;
	private final LongSupplier clock;
	private final boolean signs;
	/* The state the store holds, as far as this node knows: what it loaded or last saved, null when there is none */
	private SignState onDisk;

	/* A null seed is an observer: it follows and commits the decisions, never votes, and neither reads nor writes
	   the store */
	public LedgerNode(Ledger ledger, byte[] seed, Timeouts timeouts, SignStore store, LongSupplier clock) {
		this.ledger = ledger;
		this.engine = new Consensus(ledger, ledger.chainId(), seed, timeouts);
		this.store = store;
		this.clock = clock;
		this.signs = seed != null;
	}

	/* The state of the next height is restored. A state of a height at or below the tip is not used: that height is
	   decided, and nothing in it can be signed again. A state above the next height is a block log that is behind it,
	   as the state is saved only after the block before it is committed, so the node has signed at heights it cannot
	   show and starting afresh could sign again at them: that ends the start. So does a state that is damaged, or that
	   this node cannot have written, as it may hide a signature. */
	public synchronized List<Action> start() throws IOException {
		final long next = ledger.height() + 1;
		final Optional<SignState> kept = keptFor(next);

		return run(at -> kept.isPresent() ? engine.restore(kept.get(), ledger.validators(), at)
			: engine.start(next, ledger.validators(), at));
	}

	private Optional<SignState> keptFor(long next) throws IOException {
		final Optional<SignState> stored = signs ? store.load() : Optional.empty();

		onDisk = stored.orElse(null);

		if (stored.isPresent()) {
			requireNotAhead(stored.get(), next);
		}

		return stored.filter(state -> state.height() == next);
	}

	private static void requireNotAhead(SignState state, long next) throws BlockLogBehindException {
		if (state.height() > next) {
			throw new BlockLogBehindException("The block log is behind the sign state: the ledger is at height "
				+ (next - 1) + ", and the sign state is of height " + state.height());
		}
	}

	public synchronized List<Action> onMessage(Message message) throws IOException {
		return run(at -> engine.onMessage(message, at));
	}

	public synchronized List<Action> onTimeout(Timeout timeout) throws IOException {
		return run(at -> engine.onTimeout(timeout, at));
	}

	/* What the driver calls about once a second: whatever the engine needs the time for, such as a mint that has
	   become due, and the messages of a node at rest that its peers may have lost. What was signed already is said
	   first, so that the proposal this tick makes is not said twice. */
	public synchronized List<Action> tick() throws IOException {
		return run(at -> {
			final List<Action> actions = new ArrayList<>(engine.resume(at));

			actions.addAll(engine.onWork(at));
			return actions;
		});
	}

	/* Only a question to the ledger, which has its own lock, so it takes no turn of the node. That is a call of its
	   own from onWork(), which tells the engine that there may be something to propose: an event that can make it
	   sign, so it has to be saved. The driver calls onWork() after a submit that was accepted. */
	public Optional<String> submit(Transaction transaction) {
		return ledger.submit(transaction);
	}

	public synchronized List<Action> onWork() throws IOException {
		return run(engine::onWork);
	}

	public Ledger ledger() {
		return ledger;
	}

	/* The height the node is agreeing on, one above the tip of the ledger once it has started, and 0 before */
	public synchronized long height() {
		return engine.height();
	}

	public synchronized List<Message> outbox() {
		return engine.outbox();
	}

	/* The clock is read once, so that one call has one time. A quorum of one can decide again as soon as the next
	   height starts, so the commit goes on for as long as the engine returns a block. */
	private List<Action> run(LongFunction<List<Action>> event) throws IOException {
		final long at = clock.getAsLong();
		final List<Action> actions = new ArrayList<>(event.apply(at));
		Optional<Block> decided = decidedIn(actions);

		while (decided.isPresent()) {
			ledger.commit(decided.get());

			final List<Action> next = engine.start(ledger.height() + 1, ledger.validators(), at);

			actions.addAll(next);
			decided = decidedIn(next);
		}

		persist();
		return actions;
	}

	private static Optional<Block> decidedIn(List<Action> actions) {
		return actions.stream().filter(Decide.class::isInstance).map(Decide.class::cast).map(Decide::block)
			.findFirst();
	}

	/* An engine that has not started holds nothing, and saving that would wipe the state a start is yet to restore. A
	   state that is on disk already is not written again, which spares a quiet node a write and a flush every tick. It
	   is counted as on disk only once the store has returned, so a save that fails is tried again by the next call. */
	private void persist() throws IOException {
		if (!signs || engine.height() == 0) {
			return;
		}

		final SignState snapshot = engine.snapshot();

		if (!snapshot.equals(onDisk)) {
			store.save(snapshot, ledger.validators().size());
			onDisk = snapshot;
		}
	}
}
