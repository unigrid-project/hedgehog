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
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* What a node hands to a peer that is behind: its committed blocks, the hash it has at a height, and where its tip is */
public class LedgerServeTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final int BLOCKS = 5;

	private static Path logOf(FileSystem fileSystem) {
		return fileSystem.getPath("/data/blocks.log");
	}

	private static Ledger open(FileSystem fileSystem) throws IOException {
		return new Ledger(state(1000, ANY_MINT, FOUNDATION), BlockLog.open(logOf(fileSystem)));
	}

	/* One block for each mint, committed in order */
	private static List<Block> seal(Ledger ledger, int count) throws IOException {
		final List<Block> sealed = new ArrayList<>();

		for (int n = 1; n <= count; n++) {
			ledger.submit(mint(n, 10));

			final Block block = nextBlock(ledger, FOUNDATION, 2000 + n);

			ledger.commit(block);
			sealed.add(block);
		}

		return sealed;
	}

	private static int sizeOf(Block block) {
		return BlockCodec.encode(block).length;
	}

	private static List<Long> heights(List<Block> blocks) {
		return blocks.stream().map(Block::getHeight).toList();
	}

	@Example
	public void shouldGiveTheBlocksFromAHeightInOrder() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);
			final List<Block> sealed = seal(ledger, BLOCKS);

			assertThat(ledger.blocksFrom(1, 3), equalTo(sealed.subList(0, 3)));
			assertThat(heights(ledger.blocksFrom(1, 3)), contains(1L, 2L, 3L));
			assertThat(ledger.blocksFrom(4, 10), equalTo(sealed.subList(3, 5)));
			assertThat(ledger.blocksFrom(5, 1), equalTo(sealed.subList(4, 5)));
			assertThat(ledger.blocksFrom(1, BLOCKS), equalTo(sealed));
		}
	}

	@Example
	public void shouldGiveNothingFromAboveTheTip() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.blocksFrom(1, 3), is(empty()));

			seal(ledger, BLOCKS);

			assertThat(ledger.blocksFrom(BLOCKS + 1, 1), is(empty()));
			assertThat(ledger.blocksFrom(1000, 16), is(empty()));
			assertThat(ledger.blocksFrom(Long.MAX_VALUE, Integer.MAX_VALUE), is(empty()));
		}
	}

	@Example
	public void shouldRefuseARequestForNoBlocksOrFromBelowTheFirstHeight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			seal(ledger, BLOCKS);

			assertThrows(IllegalArgumentException.class, () -> ledger.blocksFrom(1, 0));
			assertThrows(IllegalArgumentException.class, () -> ledger.blocksFrom(1, -1));
			assertThrows(IllegalArgumentException.class, () -> ledger.blocksFrom(0, 1));
			assertThrows(IllegalArgumentException.class, () -> ledger.blocksFrom(-5, 1));
		}
	}

	@Example
	public void shouldStopBeforeTheEncodedBlocksPassTheByteBound() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);
			final List<Block> sealed = seal(ledger, BLOCKS);
			final int firstThree = sizeOf(sealed.get(0)) + sizeOf(sealed.get(1)) + sizeOf(sealed.get(2));

			assertThat(ledger.blocksFrom(1, BLOCKS, firstThree), equalTo(sealed.subList(0, 3)));
			assertThat(ledger.blocksFrom(1, BLOCKS, firstThree - 1), equalTo(sealed.subList(0, 2)));
			assertThat(ledger.blocksFrom(1, BLOCKS, firstThree + sizeOf(sealed.get(3)) - 1),
				equalTo(sealed.subList(0, 3)));
			assertThat(ledger.blocksFrom(3, BLOCKS, sizeOf(sealed.get(2)) + sizeOf(sealed.get(3))),
				equalTo(sealed.subList(2, 4)));
			assertThat(ledger.blocksFrom(1, BLOCKS, Integer.MAX_VALUE), equalTo(sealed));
		}
	}

	@Example
	public void shouldGiveNothingWhenTheFirstBlockAloneIsOverTheByteBound() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);
			final List<Block> sealed = seal(ledger, BLOCKS);

			assertThat(ledger.blocksFrom(1, BLOCKS, sizeOf(sealed.get(0)) - 1), is(empty()));
			assertThat(ledger.blocksFrom(1, BLOCKS, 0), is(empty()));
		}
	}

	@Example
	public void shouldServeTheBlocksOfALogItWasReplayedFrom() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger first = open(fileSystem);
			final List<Block> sealed = seal(first, BLOCKS);
			final Ledger replayed = open(fileSystem);

			assertThat(replayed.blocksFrom(2, 3), equalTo(sealed.subList(1, 4)));
			assertThat(replayed.hashAt(BLOCKS).orElseThrow(), equalTo(sealed.get(BLOCKS - 1).hash()));
		}
	}

	@Example
	public void shouldNotMoveTheLedgerWhenServingBlocks() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			seal(ledger, BLOCKS);

			final byte[] root = ledger.stateRoot();

			ledger.blocksFrom(1, BLOCKS);
			ledger.hashAt(BLOCKS);

			assertThat(ledger.height(), equalTo((long) BLOCKS));
			assertThat(ledger.stateRoot(), equalTo(root));
			assertThat(ledger.pending(), equalTo(0));
		}
	}

	@Example
	public void shouldGiveTheHashOfTheCommittedBlockAtAHeight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);
			final List<Block> sealed = seal(ledger, BLOCKS);

			for (int height = 1; height <= BLOCKS; height++) {
				assertThat(ledger.hashAt(height).orElseThrow(), equalTo(sealed.get(height - 1).hash()));
			}
		}
	}

	@Example
	public void shouldHaveNoHashAtGenesisAboveTheTipOrBelowTheFirstHeight() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.hashAt(0), equalTo(Optional.empty()));
			assertThat(ledger.hashAt(1), equalTo(Optional.empty()));

			seal(ledger, BLOCKS);

			assertThat(ledger.hashAt(0), equalTo(Optional.empty()));
			assertThat(ledger.hashAt(BLOCKS + 1), equalTo(Optional.empty()));
			assertThat(ledger.hashAt(-1), equalTo(Optional.empty()));
			assertThat(ledger.hashAt(Long.MAX_VALUE), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldSayThatTheTipIsTheChainIdAtGenesis() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);

			assertThat(ledger.status(), equalTo(new Status(ledger.chainId(), 0, ledger.chainId())));
		}
	}

	@Example
	public void shouldSayWhereTheTipIsAfterSomeBlocks() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final Ledger ledger = open(fileSystem);
			final List<Block> sealed = seal(ledger, BLOCKS);

			final byte[] tip = sealed.get(BLOCKS - 1).hash();

			assertThat(ledger.status(), equalTo(new Status(ledger.chainId(), BLOCKS, tip)));
			assertThat(ledger.status().tipHash(), equalTo(ledger.tipHash()));
		}
	}
}
