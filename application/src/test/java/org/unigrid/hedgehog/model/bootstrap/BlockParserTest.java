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
import static org.hamcrest.Matchers.greaterThan;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;

public class BlockParserTest {
	private static final String GENESIS_DISPLAY_HASH =
		"00000416490cfdea94f5f78bc14285e48c78c42ea8ec9c8a623050d0175cf2d2";

	@Example
	@SneakyThrows
	public void shouldReadEveryBlockInTheFixture() {
		assertThat(headers().size(), equalTo(BlockFixture.BLOCK_COUNT));
	}

	@Example
	@SneakyThrows
	public void shouldParseTheGenesisBlock() {
		final BlockHeader genesis = headers().get(0);

		assertThat(genesis.getVersion(), equalTo(1));
		assertThat(genesis.getTime(), equalTo(1536062400));
		assertThat(genesis.getBits(), equalTo(0x1e0fffff));
		assertThat(genesis.getNonce(), equalTo(280211));
		assertThat(BlockParser.toDisplayString(genesis.getHash()), equalTo(GENESIS_DISPLAY_HASH));
		assertThat(BlockParser.isGenesis(genesis.getHash()), equalTo(true));
	}

	@Example
	@SneakyThrows
	public void shouldChainEveryBlockToItsPredecessor() {
		final List<BlockHeader> headers = headers();

		for (int i = 1; i < headers.size(); i++) {
			assertThat(headers.get(i).getPreviousHash(), equalTo(headers.get(i - 1).getHash()));
			assertThat(headers.get(i).getVersion(), equalTo(4));
		}
	}

	@Example
	@SneakyThrows
	public void shouldParseTheGenesisCoinbaseTransaction() {
		final BlockFileStore store = BlockFileStore.open(BlockFixture.directory());
		final List<LegacyTransaction> transactions = new ArrayList<>();

		store.forEachBlock(location -> {
			if (transactions.isEmpty()) {
				final ByteBuffer block = store.read(location);

				BlockParser.header(block);
				transactions.addAll(BlockParser.transactions(block));
			}
		});

		assertThat(transactions.size(), equalTo(1));
		assertThat(transactions.get(0).isCoinBase(), equalTo(true));
		assertThat(transactions.get(0).getInputs().get(0).getType(), equalTo(InputType.COINBASE));
		assertThat(transactions.get(0).getOutputs().get(0).getValue(), equalTo(0L));
		assertThat(transactions.get(0).getOutputs().get(0).getType(), equalTo(OutputType.ADDRESS));
		assertThat(transactions.get(0).getOutputs().get(0).isSpendable(), equalTo(false));
	}

	@Example
	@SneakyThrows
	public void shouldParseThePremineInTheSecondBlock() {
		final List<LegacyTransaction> transactions = transactionsAtHeight(1);

		assertThat(transactions.size(), equalTo(1));
		assertThat(transactions.get(0).getOutputs().size(), greaterThan(1));
		assertThat(transactions.get(0).getOutputs().get(0).getValue(), greaterThan(0L));
		assertThat(transactions.get(0).getOutputs().get(0).getType(), equalTo(OutputType.ADDRESS));
	}

	@SneakyThrows
	private static List<LegacyTransaction> transactionsAtHeight(int height) {
		final BlockFileStore store = BlockFileStore.open(BlockFixture.directory());
		final List<BlockLocation> locations = new ArrayList<>();

		store.forEachBlock(locations::add);

		final ByteBuffer block = store.read(locations.get(height));

		BlockParser.header(block);
		return BlockParser.transactions(block);
	}

	@SneakyThrows
	private static List<BlockHeader> headers() {
		final BlockFileStore store = BlockFileStore.open(BlockFixture.directory());
		final List<BlockHeader> headers = new ArrayList<>();

		store.forEachBlock(location -> headers.add(BlockParser.header(store.read(location))));
		return headers;
	}
}
