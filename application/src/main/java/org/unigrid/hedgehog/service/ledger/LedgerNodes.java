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

package org.unigrid.hedgehog.service.ledger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.ledger.BlockLog;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerDriver.NodeFactory;
import org.unigrid.hedgehog.ledger.LedgerNode;
import org.unigrid.hedgehog.ledger.LedgerState;
import org.unigrid.hedgehog.ledger.SignStateFile;
import org.unigrid.hedgehog.ledger.Timeouts;

/* The nodes of the driver over the files of this node, which one node at a time uses. The driver asks for an observer
   first and, once it has caught up, for a node that signs, and it has closed the one before when it asks for the next.
   That closes the sign state and no more, so the block log of the node before is closed here, and the next node
   replays the log again into a state of its own: the replay is the cost of a node, and happens on the thread that
   asks for it, which is the driver's own. The newest ledger is kept for the readers of the application, who may be on
   any thread; a ledger stays readable after its node is gone. A key is the seed of a validator, and is held no
   longer than the nodes are. */
@Slf4j
@RequiredArgsConstructor
final class LedgerNodes implements NodeFactory, AutoCloseable {
	static final String BLOCK_LOG = "blocks.log";
	static final String SIGN_STATE = "sign.state";

	private final Path directory;
	private final Supplier<LedgerState> states;
	private final byte[] seed;
	private final LongSupplier clock;
	private BlockLog blockLog;
	private boolean closed;
	private volatile Ledger ledger;
	private volatile boolean signs;

	@Override
	public synchronized LedgerNode create(final boolean signing) throws IOException {
		if (closed) {
			throw new IOException("The nodes of the ledger are closed");
		}

		closeBlockLog();

		final Path file = directory.resolve(BLOCK_LOG);

		blockLog = open(file);

		final Ledger created = replay(file);
		final LedgerNode node = new LedgerNode(created, signing ? seed : null, Timeouts.DEFAULT,
			signing ? new SignStateFile(directory.resolve(SIGN_STATE)) : null, clock);

		ledger = created;
		signs = node.signs();
		return node;
	}

	/* Empty until the first node has been built */
	Optional<Ledger> ledger() {
		return Optional.ofNullable(ledger);
	}

	/* Whether the newest node was built to sign */
	boolean signing() {
		return signs;
	}

	/* Waits for a node that is being built, which has the log open, and then no node is built any more */
	@Override
	public synchronized void close() {
		closed = true;

		try {
			closeBlockLog();
		} catch (IOException ex) {
			log.atWarn().addArgument(directory.resolve(BLOCK_LOG)).addArgument(ex.getMessage())
				.log("Could not close the block log {}: {}");
		}
	}

	private void closeBlockLog() throws IOException {
		final BlockLog previous = blockLog;

		blockLog = null;

		if (previous != null) {
			previous.close();
		}
	}

	private static BlockLog open(final Path file) throws IOException {
		try {
			return BlockLog.open(file);
		} catch (IOException ex) {
			throw new IOException("Cannot open the block log " + file + ": " + ex.getMessage(), ex);
		}
	}

	private Ledger replay(final Path file) throws IOException {
		try {
			return new Ledger(states.get(), blockLog, clock);
		} catch (RuntimeException ex) {
			throw new IOException("Cannot replay the block log " + file + ": " + ex.getMessage(), ex);
		}
	}
}
