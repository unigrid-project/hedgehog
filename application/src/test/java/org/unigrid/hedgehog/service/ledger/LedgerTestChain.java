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
import java.nio.ByteBuffer;
import java.nio.file.FileSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockLog;
import org.unigrid.hedgehog.ledger.Certificate;
import org.unigrid.hedgehog.ledger.Claim;
import org.unigrid.hedgehog.ledger.Decision;
import org.unigrid.hedgehog.ledger.Ed25519;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerAuthority;
import org.unigrid.hedgehog.ledger.LedgerGenesis;
import org.unigrid.hedgehog.ledger.LedgerNode;
import org.unigrid.hedgehog.ledger.LedgerState;
import org.unigrid.hedgehog.ledger.Mint;
import org.unigrid.hedgehog.ledger.Reference;
import org.unigrid.hedgehog.ledger.Timeouts;
import org.unigrid.hedgehog.ledger.Transfer;
import org.unigrid.hedgehog.ledger.ValidatorSet;

/* A chain of four validators whose blocks are made by a ledger of its own. The ledger module keeps the means of
   committing a block to itself, so the blocks are given to a node as decisions, which is how a node that is behind
   gets them. Every block holds one mint, as a ledger makes a block only when it has something to put in it. */
public final class LedgerTestChain {
	public static final long CLOCK_START = 10_000;
	public static final List<byte[]> SEEDS = IntStream.rangeClosed(1, 4).mapToObj(LedgerTestChain::seed).toList();

	private static final long GENESIS_TIME = 1000;
	private static final int ROUND_LENGTH = 1000;
	private static final long MINT_AMOUNT = 100;

	/* Anything is allowed and nothing is due */
	private static final LedgerAuthority OPEN = new LedgerAuthority() {
		@Override
		public List<Mint> due(final long time) {
			return List.of();
		}

		@Override
		public boolean authorizes(final Mint mint, final long time, final long supply) {
			return true;
		}

		@Override
		public boolean authorizes(final Claim claim) {
			return true;
		}

		@Override
		public boolean authorizes(final Transfer transfer) {
			return true;
		}
	};

	private LedgerTestChain() {
		/* Static helpers only */
	}

	public static byte[] seed(final int n) {
		return ByteBuffer.allocate(Ed25519.SEED_SIZE).putInt(n).array();
	}

	public static LedgerState state() {
		return new LedgerState(new LedgerGenesis(GENESIS_TIME, ROUND_LENGTH,
			new ValidatorSet(SEEDS.stream().map(Ed25519::publicKey).toList())), OPEN);
	}

	/* A mint that no other number makes: the ledger lets a reference be used once */
	public static Mint mint(final int n) {
		final byte[] reference = new byte[Reference.SIZE];

		ByteBuffer.wrap(reference).putInt(n);
		return new Mint(Ed25519.publicKey(seed(1000 + n)), MINT_AMOUNT, new Reference(reference));
	}

	/* The blocks of heights 1 to `count`, each certified by a quorum of the validators */
	@SneakyThrows
	public static List<Block> blocks(final int count) {
		final LongSupplier clock = () -> CLOCK_START;

		try (FileSystem files = Jimfs.newFileSystem(Configuration.unix());
			BlockLog log = BlockLog.open(files.getPath("/chain/blocks.log"))) {

			final Ledger ledger = new Ledger(state(), log, clock);
			final LedgerNode node = new LedgerNode(ledger, null, Timeouts.DEFAULT, null, clock);
			final List<Block> blocks = new ArrayList<>();

			node.start();

			for (int n = 1; n <= count; n++) {
				ledger.submit(mint(n)).ifPresent(reason -> {
					throw new IllegalStateException("The chain refused its own mint: " + reason);
				});

				final long height = ledger.height() + 1;
				final Block proposed = ledger.propose(height, 0, ledger.validators().proposerAt(height),
					CLOCK_START).orElseThrow();
				final Block block = certified(proposed, ledger);

				node.onMessage(new Decision(block));
				blocks.add(block);
			}

			return blocks;
		}
	}

	private static Block certified(final Block proposed, final Ledger ledger) {
		Block block = proposed.toBuilder().certificate(new Certificate(proposed.getView(), List.of())).build();

		for (final byte[] seed : SEEDS.subList(0, ledger.validators().quorum())) {
			block = block.endorsedBy(seed, ledger.chainId());
		}

		return block;
	}

	public static byte[] tipOf(final List<Block> blocks) {
		return blocks.get(blocks.size() - 1).hash();
	}
}
