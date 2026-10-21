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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.SneakyThrows;
import mockit.Invocation;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.ledger.Block;
import org.unigrid.hedgehog.ledger.BlockLog;
import org.unigrid.hedgehog.ledger.BlockLogBehindException;
import org.unigrid.hedgehog.ledger.Ledger;
import org.unigrid.hedgehog.ledger.LedgerNode;
import org.unigrid.hedgehog.ledger.SignState;
import org.unigrid.hedgehog.ledger.SignStateFile;

public class LedgerNodesTest {
	private static final int BLOCKS = 3;
	private static final int VALIDATORS = LedgerTestChain.SEEDS.size();
	private static final byte[] SEED = LedgerTestChain.SEEDS.get(0);
	private static final List<Block> CHAIN = LedgerTestChain.blocks(BLOCKS);

	private static FileSystem files() {
		return Jimfs.newFileSystem(Configuration.unix());
	}

	private static Path directory(final FileSystem files) {
		return files.getPath("/data/ledger");
	}

	private static LedgerNodes nodes(final FileSystem files, final byte[] seed) {
		return new LedgerNodes(directory(files), LedgerTestChain::state, seed, () -> LedgerTestChain.CLOCK_START);
	}

	@SneakyThrows
	private static void store(final FileSystem files, final List<Block> blocks) {
		try (BlockLog log = BlockLog.open(directory(files).resolve(LedgerNodes.BLOCK_LOG))) {
			for (final Block block : blocks) {
				log.append(block);
			}
		}
	}

	@Example
	@SneakyThrows
	public void shouldBuildAnObserverThatHoldsTheBlocksOfTheLogWithoutAKey() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, null)) {
			store(files, CHAIN);

			final LedgerNode node = nodes.create(false);

			assertThat(node.signs(), is(false));
			assertThat(nodes.signing(), is(false));
			assertThat(nodes.ledger().orElseThrow().height(), equalTo((long) BLOCKS));
			assertThat(nodes.ledger().orElseThrow().tipHash(), equalTo(LedgerTestChain.tipOf(CHAIN)));
		}
	}

	@Example
	@SneakyThrows
	public void shouldHaveNoLedgerUntilTheFirstNodeIsBuilt() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			assertThat(nodes.ledger().isPresent(), is(false));
			assertThat(nodes.signing(), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void shouldBuildANodeThatSignsFromTheSeedAndSayThatTheNewestNodeSigns() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			assertThat(nodes.create(false).signs(), is(false));
			assertThat(nodes.signing(), is(false));

			assertThat(nodes.create(true).signs(), is(true));
			assertThat(nodes.signing(), is(true));

			assertThat(nodes.create(false).signs(), is(false));
			assertThat(nodes.signing(), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void shouldReplayTheLogAgainForTheNodeThatSignsAndHandOnTheNewerLedger() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			store(files, CHAIN);

			nodes.create(false);

			final Ledger observer = nodes.ledger().orElseThrow();

			nodes.create(true);

			assertThat(nodes.ledger().orElseThrow(), not(sameInstance(observer)));
			assertThat(nodes.ledger().orElseThrow().height(), equalTo((long) BLOCKS));
		}
	}

	@Example
	@SneakyThrows
	public void shouldCloseTheBlockLogOfTheNodeBeforeItOpensTheNextAndTheLastWhenClosed() {
		final List<String> events = new CopyOnWriteArrayList<>();

		new MockUp<BlockLog>() {
			@Mock
			public BlockLog open(final Invocation invocation, final Path file) throws IOException {
				events.add("open");
				return invocation.proceed(file);
			}

			@Mock
			public void close(final Invocation invocation) throws IOException {
				events.add("close");
				invocation.proceed();
			}
		};

		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			nodes.create(false);
			assertThat(events, contains("open"));

			nodes.create(true);
			assertThat(events, contains("open", "close", "open"));

			nodes.close();
			assertThat(events, contains("open", "close", "open", "close"));

			nodes.close();
			assertThat(events, contains("open", "close", "open", "close"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldCloseWithoutHavingBuiltAnything() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, null)) {
			nodes.close();
			nodes.close();
		}
	}

	/* A replay that outlasts the wait for the driver to close is let finish by the close, and a node that is asked for
	   after it must not open the log again: nobody would close it */
	@Example
	@SneakyThrows
	public void shouldBuildNoMoreNodesOnceClosedAndOpenNoLog() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			nodes.close();

			assertThat(assertThrows(IOException.class, () -> nodes.create(false)).getMessage(),
				containsString("closed"));
			assertThat(assertThrows(IOException.class, () -> nodes.create(true)).getMessage(),
				containsString("closed"));
			assertThat(Files.exists(directory(files).resolve(LedgerNodes.BLOCK_LOG)), is(false));
			assertThat(nodes.ledger().isPresent(), is(false));
		}
	}

	@Example
	@SneakyThrows
	public void shouldKeepTheLedgerItHadAfterItIsClosed() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, null)) {
			store(files, CHAIN);
			nodes.create(false);
			nodes.close();

			assertThat(nodes.ledger().orElseThrow().height(), equalTo((long) BLOCKS));
		}
	}

	@Example
	@SneakyThrows
	public void shouldGiveEachNodeAStateOfItsOwn() {
		final AtomicInteger made = new AtomicInteger();

		try (FileSystem files = files(); LedgerNodes nodes = new LedgerNodes(directory(files), () -> {
			made.incrementAndGet();
			return LedgerTestChain.state();
		}, SEED, () -> LedgerTestChain.CLOCK_START)) {

			nodes.create(false);
			nodes.create(true);
			nodes.create(false);

			assertThat(made.get(), is(3));
		}
	}

	@Example
	@SneakyThrows
	public void shouldKeepTheSignStateWhereTheNodeThatSignsLooksForIt() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			store(files, CHAIN);

			try (SignStateFile state = new SignStateFile(directory(files).resolve(LedgerNodes.SIGN_STATE))) {
				state.save(SignState.empty(BLOCKS + 2), VALIDATORS);
			}

			final LedgerNode signer = nodes.create(true);

			assertThat(assertThrows(BlockLogBehindException.class, signer::start).getMessage(),
				containsString("The block log is behind the sign state"));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNotLetAnObserverReadTheSignStateOfTheKey() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, SEED)) {
			store(files, CHAIN);

			try (SignStateFile state = new SignStateFile(directory(files).resolve(LedgerNodes.SIGN_STATE))) {
				state.save(SignState.empty(BLOCKS + 2), VALIDATORS);
			}

			nodes.create(false).start();

			assertThat(nodes.ledger().orElseThrow().height(), equalTo((long) BLOCKS));
		}
	}

	@Example
	@SneakyThrows
	public void shouldNameTheBlockLogThatCannotBeReadAndKeepTheLedgerItHad() {
		try (FileSystem files = files(); LedgerNodes nodes = nodes(files, null)) {
			store(files, CHAIN.subList(0, 2));
			nodes.create(false);

			final Ledger before = nodes.ledger().orElseThrow();
			final Path log = directory(files).resolve(LedgerNodes.BLOCK_LOG);
			final byte[] bytes = Files.readAllBytes(log);

			bytes[bytes.length / 4] ^= 0x55;
			Files.write(log, bytes);

			final IOException refusal = assertThrows(IOException.class, () -> nodes.create(false));

			assertThat(refusal.getMessage(), containsString(log.toString()));
			assertThat(nodes.ledger().orElseThrow(), sameInstance(before));
		}
	}
}
