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
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/* A ledger node over the two files of a validator, a block log and a sign state, which are all it keeps. A restart is a
   new node over a new ledger that the block log is replayed into, as a process that starts again would have it; the
   pool of waiting transactions is lost with the old node, which is what senders have to make good. The node saves
   what an event signed before it returns the actions of the event, which the simulator then performs. */
final class LedgerProcess implements SimProcess {
	@FunctionalInterface
	private interface Io<T> {
		T run() throws IOException;
	}

	private final Path directory;
	private final Supplier<LedgerState> chain;
	private final byte[] seed;
	private final Timeouts timeouts;
	private final LongSupplier clock;
	private BlockLog log;
	private SignStateFile signs;
	private Ledger ledger;
	private LedgerNode node;
	private int restarts;

	LedgerProcess(Path directory, Supplier<LedgerState> chain, byte[] seed, Timeouts timeouts, LongSupplier clock) {
		this.directory = directory;
		this.chain = chain;
		this.seed = seed;
		this.timeouts = timeouts;
		this.clock = clock;
		open();
	}

	private static <T> T io(Io<T> call) {
		try {
			return call.run();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static long heightOf(Message message) {
		return switch (message) {
			case Ballot ballot -> ballot.height();
			case Proposal proposal -> proposal.height();
			case Decision decision -> decision.block().getHeight();
		};
	}

	private void open() {
		log = io(() -> BlockLog.open(directory.resolve("blocks.log")));
		signs = new SignStateFile(directory.resolve("sign.state"));
		ledger = new Ledger(chain.get(), log, clock);
		node = new LedgerNode(ledger, seed, timeouts, signs, clock);
	}

	LedgerNode node() {
		return node;
	}

	Ledger ledger() {
		return ledger;
	}

	int restarts() {
		return restarts;
	}

	@Override
	public boolean started() {
		return node.height() != 0;
	}

	@Override
	public List<Action> start() {
		return io(node::start);
	}

	@Override
	public List<Action> startFromDisk() {
		io(() -> {
			log.close();
			return null;
		});
		restarts++;
		open();
		return start();
	}

	@Override
	public List<Action> onMessage(Message message) {
		return io(() -> node.onMessage(message));
	}

	@Override
	public List<Action> onTimeout(Timeout timeout) {
		return io(() -> node.onTimeout(timeout));
	}

	@Override
	public List<Action> onWork() {
		return io(node::onWork);
	}

	@Override
	public List<Action> tick() {
		return io(node::tick);
	}

	@Override
	public List<Action> decided(Block block) {
		return List.of();
	}

	@Override
	public List<Block> committed() {
		return log.blocks();
	}

	/* What the file holds now, which is the state of the height the node is agreeing on */
	SignState kept() {
		return io(signs::load).orElseThrow();
	}

	/* What the node signed for a height it has passed is not kept, so it is not looked for in the file */
	@Override
	public boolean saved(Message signed) {
		if (heightOf(signed) <= ledger.height()) {
			return true;
		}

		final SignState state = kept();

		return state.ballots().contains(signed) || state.proposals().contains(signed);
	}

	@Override
	public AccountKey self() {
		return Ed25519.publicKey(seed);
	}
}
