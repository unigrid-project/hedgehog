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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import lombok.SneakyThrows;
import net.jqwik.api.Example;

public class ChainLinkerTest {
	private static final int VERSION_WITH_ACCUMULATOR = 4;

	@Example
	@SneakyThrows
	public void shouldLinkTheFixtureIntoOneUnbrokenChain() {
		final Chain chain = ChainLinker.link(BlockFileStore.open(BlockFixture.directory()));

		assertThat(chain.getBlockCount(), equalTo(BlockFixture.BLOCK_COUNT));
		assertThat(chain.getTipHeight(), equalTo(BlockFixture.BLOCK_COUNT - 1));
		assertThat(chain.getStaleBlockCount(), equalTo(0));
	}

	@Example
	@SneakyThrows
	public void shouldKeepTheDeeperBranchAndDiscardTheStaleOne() {
		final Chain chain = ChainLinker.link(SyntheticBlocks.store(builder -> {
			builder.mainChain(6);
			builder.fork(3, 1);
			builder.fork(4, 1);
		}));

		assertThat(chain.getBlockCount(), equalTo(7));
		assertThat(chain.getTipHeight(), equalTo(6));
		assertThat(chain.getStaleBlockCount(), equalTo(2));
	}

	@Example
	@SneakyThrows
	public void shouldFollowTheDeepestBranchWhenItIsNotTheFirstOne() {
		final Chain chain = ChainLinker.link(SyntheticBlocks.store(builder -> {
			builder.mainChain(3);
			builder.fork(1, 6);
		}));

		assertThat(chain.getTipHeight(), equalTo(7));
		assertThat(chain.getStaleBlockCount(), equalTo(2));
	}

	@Example
	@SneakyThrows
	public void shouldOrderTheChainByHeight() {
		final BlockFileStore store = SyntheticBlocks.store(builder -> builder.mainChain(4));
		final Chain chain = ChainLinker.link(store);

		for (int height = 0; height <= chain.getTipHeight(); height++) {
			final ByteBuffer block = store.read(chain.locationAt(height));
			final BlockHeader header = BlockParser.header(block);

			assertThat(header.getTime(), equalTo(SyntheticBlocks.timeAt(height)));
		}
	}

	/*
	   Files that hold no record with the network magic, such as the data directory of another chain or
	   preallocated files that were never written to, leave nothing to link.
	*/
	@Example
	@SneakyThrows
	public void shouldSayThatNoBlockWasFoundWhenTheFilesHoldNone() {
		final Throwable refusal = refusalOf(SyntheticBlocks.store(builder -> { }));

		assertThat(refusal, instanceOf(IllegalStateException.class));
		assertThat(refusal.getMessage(), containsString("network magic"));
	}

	/*
	   A chain whose first files are missing replays into spends of outputs that were never created, so
	   it is turned away here, before the replay has run for minutes.
	*/
	@Example
	@SneakyThrows
	public void shouldRefuseBlocksThatDoNotLeadBackToTheGenesisBlock() {
		final Throwable refusal = refusalOf(SyntheticBlocks.store(builder -> builder.detachedChain(5)));

		assertThat(refusal, instanceOf(IllegalStateException.class));
		assertThat(refusal.getMessage(), containsString("genesis block"));
	}

	@Example
	@SneakyThrows
	public void shouldNotLetADeeperDetachedBranchReplaceTheChainFromTheGenesisBlock() {
		final Chain chain = ChainLinker.link(SyntheticBlocks.store(builder -> {
			builder.mainChain(3);
			builder.detachedChain(9);
		}));

		assertThat(chain.getTipHeight(), equalTo(3));
		assertThat(chain.getBlockCount(), equalTo(4));
		assertThat(chain.getStaleBlockCount(), equalTo(10));
	}

	/*
	   A record is taken for a block once it starts with the network magic and fits its file, so one
	   that holds less than a header is first found out when its header is read. Which record that is
	   can only be told by its file and its place in it.
	*/
	@Example
	@SneakyThrows
	public void shouldNameTheRecordThatIsShorterThanAHeader() {
		final BlockFileStore store = SyntheticBlocks.store(builder -> builder.record(new byte[8]));
		final Throwable refusal = refusalOf(store);

		assertThat(refusal, instanceOf(IllegalStateException.class));
		assertThat(refusal.getMessage(), equalTo("The block at offset 8 of " + store.getPaths().get(0)
			+ " is damaged and cannot be read"));
	}

	/* The header of every block after the first ends with an accumulator checkpoint, which this one lacks */
	@Example
	@SneakyThrows
	public void shouldNameTheRecordThatEndsBeforeItsAccumulatorCheckpoint() {
		final byte[] cutShort = ByteBuffer.allocate(BlockParser.headerSize(1)).order(ByteOrder.LITTLE_ENDIAN)
			.putInt(VERSION_WITH_ACCUMULATOR).array();
		final BlockFileStore store = SyntheticBlocks.store(builder -> {
			builder.mainChain(2);
			builder.record(cutShort);
		});
		final Throwable refusal = refusalOf(store);

		assertThat(refusal, instanceOf(IllegalStateException.class));
		assertThat(refusal.getMessage(), equalTo("The block at offset " + offsetOfTheLastRecord(store) + " of "
			+ store.getPaths().get(0) + " is damaged and cannot be read"));
	}

	private static int offsetOfTheLastRecord(BlockFileStore store) {
		final int[] offset = {0};

		store.forEachBlock(location -> offset[0] = location.getOffset());
		return offset[0];
	}

	private static Throwable refusalOf(BlockFileStore store) {
		try {
			final Chain chain = ChainLinker.link(store);

			throw new AssertionError("A chain of " + chain.getBlockCount() + " blocks was linked");

		} catch (RuntimeException ex) {
			return ex;
		}
	}
}
