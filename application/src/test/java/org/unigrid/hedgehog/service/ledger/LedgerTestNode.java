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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockLog;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerDriver;
import org.unigrid.hedgehog.ledger.LedgerDriver.DriverOptions;
import org.unigrid.hedgehog.ledger.LedgerNetwork;
import org.unigrid.hedgehog.ledger.LedgerNode;
import org.unigrid.hedgehog.ledger.PeerLimiter;
import org.unigrid.hedgehog.ledger.PeerLimiter.Policy;
import org.unigrid.hedgehog.ledger.SignStateFile;
import org.unigrid.hedgehog.ledger.Timeouts;

/* A ledger node of the kind a running application has: a driver over a block log and a sign state, on its own threads,
   with its files in memory. The node factory is the one that the driver asks for a node that follows and later, once
   it has caught up, for one that votes, and it closes the block log of the one before it opens the next. */
final class LedgerTestNode implements AutoCloseable {
	private final LongSupplier clock = System::currentTimeMillis;
	private final FileSystem files = Jimfs.newFileSystem(Configuration.unix());
	private final Path directory = files.getPath("/ledger");
	private final byte[] seed;
	private final List<Boolean> built = new CopyOnWriteArrayList<>();
	private final LedgerRuntime runtime;
	private final LedgerDriver driver;
	private BlockLog log;
	private volatile Ledger ledger;

	/* A node that holds `blocks` already starts as one that has been running; it votes if it has a seed */
	@SneakyThrows
	LedgerTestNode(final String name, final LedgerNetwork network, final byte[] seed, final DriverOptions options,
		final List<Block> blocks) {

		this.seed = seed;
		this.runtime = new LedgerRuntime(name);
		store(blocks);
		this.driver = new LedgerDriver(this::create, runtime, runtime, network, new PeerLimiter(clock, Policy.DEFAULT),
			clock, options, seed);
	}

	private void store(final List<Block> blocks) throws IOException {
		try (BlockLog stored = BlockLog.open(directory.resolve("blocks.log"))) {
			for (final Block block : blocks) {
				stored.append(block);
			}
		}
	}

	private synchronized LedgerNode create(final boolean signing) throws IOException {
		built.add(signing);

		if (log != null) {
			log.close();
		}

		log = BlockLog.open(directory.resolve("blocks.log"));
		ledger = new Ledger(LedgerTestChain.state(), log, clock);

		return new LedgerNode(ledger, signing ? seed : null, Timeouts.DEFAULT,
			signing ? new SignStateFile(directory.resolve("sign.state")) : null, clock);
	}

	LedgerDriver driver() {
		return driver;
	}

	/* The ledger of the newest node, which a halted driver keeps */
	Ledger ledger() {
		return ledger;
	}

	/* 0 until the driver has built its first node */
	long height() {
		final Ledger held = ledger;

		return held == null ? 0 : held.height();
	}

	/* Whether each node that the factory built was to vote, in order */
	List<Boolean> built() {
		return List.copyOf(built);
	}

	@Override
	@SneakyThrows
	public void close() {
		driver.close();
		driver.closed().join();
		runtime.close();

		synchronized (this) {
			if (log != null) {
				log.close();
			}
		}

		files.close();
	}
}
