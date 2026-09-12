/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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
import static org.hamcrest.Matchers.equalTo;
import java.nio.ByteBuffer;
import lombok.SneakyThrows;
import net.jqwik.api.Example;

public class ChainLinkerTest {
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
}
