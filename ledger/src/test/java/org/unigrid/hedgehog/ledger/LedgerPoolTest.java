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

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.ANY_MINT;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mint;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.mintsWhere;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.seeds;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.state;

/* The ledger that replaces another is handed what waited for a block in the other */
public class LedgerPoolTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);

	private static Ledger open(final FileSystem fileSystem, final String name, final LedgerAuthority authority)
		throws IOException {

		return new Ledger(state(1000, authority, FOUNDATION), BlockLog.open(fileSystem.getPath("/" + name)));
	}

	@Example
	public void shouldTakeInWhatWaitedInTheLedgerItReplacesInTheOrderItCame() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger replaced = open(fileSystem, "old", ANY_MINT);
			final Ledger replacing = open(fileSystem, "new", ANY_MINT);

			replaced.submit(mint(3, 300));
			replaced.submit(mint(1, 100));
			replaced.submit(mint(2, 200));
			replacing.takeWaitingFrom(replaced);

			assertThat(replacing.waiting(), contains(mint(3, 300), mint(1, 100), mint(2, 200)));
		}
	}

	@Example
	public void shouldLeaveOutWhatTheReplacingLedgerRefuses() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger replaced = open(fileSystem, "old", ANY_MINT);
			final Ledger replacing = open(fileSystem, "new", mintsWhere(mint -> !mint.equals(mint(2, 200))));

			replaced.submit(mint(1, 100));
			replaced.submit(mint(2, 200));
			replaced.submit(mint(3, 300));
			replacing.takeWaitingFrom(replaced);

			assertThat(replacing.waiting(), contains(mint(1, 100), mint(3, 300)));
		}
	}

	@Example
	public void shouldLeaveOutWhatMakesTheCheckOfTheReplacingLedgerThrowAndStillHaveWorkForTheRest()
		throws IOException {

		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger replaced = open(fileSystem, "old", ANY_MINT);
			final Ledger replacing = open(fileSystem, "new", mintsWhere(mint -> {
				if (mint.equals(mint(2, 200))) {
					throw new IllegalStateException("The policy cannot answer for this mint");
				}

				return true;
			}));

			replaced.submit(mint(1, 100));
			replaced.submit(mint(2, 200));
			replaced.submit(mint(3, 300));
			replacing.takeWaitingFrom(replaced);

			assertThat(replacing.waiting(), contains(mint(1, 100), mint(3, 300)));
			assertThat(replacing.hasWork(0), is(true));
		}
	}

	@Example
	public void shouldLeaveTheReplacedLedgerAsItWas() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger replaced = open(fileSystem, "old", ANY_MINT);
			final Ledger replacing = open(fileSystem, "new", ANY_MINT);

			replaced.submit(mint(1, 100));
			replacing.takeWaitingFrom(replaced);

			assertThat(replaced.waiting(), contains(mint(1, 100)));
			assertThat(replaced.pending(), is(1));
		}
	}

	@Example
	public void shouldTakeInNothingFromALedgerWithNothingWaiting() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger replacing = open(fileSystem, "new", ANY_MINT);

			replacing.takeWaitingFrom(open(fileSystem, "old", ANY_MINT));

			assertThat(replacing.waiting(), is(empty()));
		}
	}
}
