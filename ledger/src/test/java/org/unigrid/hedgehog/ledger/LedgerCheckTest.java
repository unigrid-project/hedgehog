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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

/* The authority is the application's, and the ledger asks it about what peers send. When it throws, the answer is
   that the item could not be checked, and the ledger is as it was. */
public class LedgerCheckTest {
	private static final List<byte[]> SOLO = seeds(1, 1);

	private static LedgerAuthority failingWhile(AtomicBoolean failing) {
		return TestAuthority.open().withMints((mint, time) -> {
			if (failing.get()) {
				throw new IllegalStateException("The authority is down");
			}

			return true;
		});
	}

	private static Ledger open(FileSystem fileSystem, AtomicBoolean failing) throws IOException {
		final BlockLog log = BlockLog.open(fileSystem.getPath("/data/blocks.log"));

		return new Ledger(state(1000, failingWhile(failing), SOLO), log, () -> 1);
	}

	@Example
	public void shouldRefuseATransactionItCouldNotCheckAndKeepThePoolAsItWas() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicBoolean failing = new AtomicBoolean(true);
			final Ledger ledger = open(fileSystem, failing);

			final Optional<String> refused = ledger.submit(mint(1, 500));

			assertThat(refused.orElseThrow(), startsWith("Could not be checked: "));
			assertThat(refused.orElseThrow(), containsString("The authority is down"));
			assertThat(ledger.pending(), equalTo(0));

			failing.set(false);

			assertThat(ledger.submit(mint(1, 500)), equalTo(Optional.empty()));
			assertThat(ledger.pending(), equalTo(1));
		}
	}

	@Example
	public void shouldJudgeABlockItCouldNotCheckAsRefusedWithoutFailing() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final AtomicBoolean failing = new AtomicBoolean(true);
			final Ledger ledger = open(fileSystem, failing);
			final Block block = block(state(1000, ANY_MINT, SOLO), SOLO, List.of(mint(1, 500)));

			assertThat(ledger.validate(block).orElseThrow(), startsWith("Could not be checked: "));

			failing.set(false);

			assertThat(ledger.validate(block), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldCutALongMessageOfTheFailureShort() throws IOException {
		try (FileSystem fileSystem = newFileSystem()) {
			final LedgerAuthority wordy = TestAuthority.open().withMints((mint, time) -> {
				throw new IllegalStateException("x".repeat(5000));
			});
			final Ledger ledger = new Ledger(state(1000, wordy, SOLO),
				BlockLog.open(fileSystem.getPath("/data/blocks.log")), () -> 1);

			assertThat(ledger.submit(mint(1, 500)).orElseThrow().length() < 200, equalTo(true));
		}
	}
}
