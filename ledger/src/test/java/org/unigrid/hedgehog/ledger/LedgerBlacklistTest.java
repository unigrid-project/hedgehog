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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;
import static org.unigrid.hedgehog.ledger.LedgerFixtures.*;

public class LedgerBlacklistTest {
	private static final List<byte[]> FOUNDATION = seeds(1, 3);
	private static final LegacyWallet HELD = new LegacyWallet(1, true);
	private static final LegacyWallet CLEAN = new LegacyWallet(2, true);
	private static final Map<LegacyAddress, Long> SNAPSHOT = Map.of(HELD.address(), 500L, CLEAN.address(), 300L);
	private static final long BLOCK_TIME = 2000;

	private static LedgerState chain(LedgerAuthority authority) {
		return stateWithSnapshot(authority, FOUNDATION, 800, SNAPSHOT);
	}

	private static Ledger open(FileSystem fileSystem, String name, LedgerAuthority authority) throws IOException {
		return new Ledger(chain(authority), BlockLog.open(fileSystem.getPath("/" + name + "/blocks.log")));
	}

	private static String reasonOf(Optional<String> rejection) {
		return rejection.orElse("");
	}

	private static Block proposal(Ledger ledger) {
		return FOUNDATION.stream().map(seed -> ledger.propose(seed, BLOCK_TIME)).flatMap(Optional::stream)
			.findFirst().orElseThrow();
	}

	private static Block certified(Ledger ledger) {
		Block block = proposal(ledger);

		for (final byte[] seed : FOUNDATION) {
			if (block.getEndorsements().stream().noneMatch(e -> e.signer().equals(Ed25519.publicKey(seed)))) {
				block = ledger.sign(block, seed);
			}
		}

		return block;
	}

	@Example
	public void shouldHoldBackTheClaimOfABlacklistedAddressAndLetAnotherThrough() {
		final LedgerState state = chain(blacklisting(Set.of(HELD.address())));

		assertThat(reasonOf(state.rejectionOf(HELD.claim(account(1), state.chainId()))),
			containsString("not authorized"));
		assertThat(state.rejectionOf(CLEAN.claim(account(2), state.chainId())).isPresent(), is(false));
		assertThat(state.unclaimed(), equalTo(800L));
	}

	@Example
	public void shouldRefuseABlacklistedClaimAtTheDoorOfTheMempool() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", blacklisting(Set.of(HELD.address())));

			assertThat(reasonOf(ledger.submit(HELD.claim(account(1), ledger.chainId()))),
				containsString("not authorized"));
			assertThat(ledger.submit(CLEAN.claim(account(2), ledger.chainId())), equalTo(Optional.empty()));
			assertThat(ledger.pending(), equalTo(1));
		}
	}

	@Example
	public void shouldNotSignABlockThatCarriesABlacklistedClaim() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger lenient = open(fileSystem, "a", ANY_MINT);
			final Ledger strict = open(fileSystem, "b", blacklisting(Set.of(HELD.address())));

			lenient.submit(HELD.claim(account(1), lenient.chainId()));

			final Block block = proposal(lenient);
			final byte[] other = FOUNDATION.stream().filter(seed -> !Ed25519.publicKey(seed).equals(block
				.getProposer())).findFirst().orElseThrow();
			final InvalidBlockException refused = assertThrows(InvalidBlockException.class,
				() -> strict.sign(block, other));

			assertThat(refused.getMessage(), containsString("not authorized"));
		}
	}

	/* The blacklist is policy of a node, not a rule of the chain: a block that the validators signed is applied
	   by every node, or two nodes with different lists would disagree about the same chain */
	@Example
	public void shouldApplyACertifiedBlockWithABlacklistedClaimOnANodeThatHoldsTheList() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger lenient = open(fileSystem, "a", ANY_MINT);
			final Ledger strict = open(fileSystem, "b", blacklisting(Set.of(HELD.address())));

			lenient.submit(HELD.claim(account(1), lenient.chainId()));

			final Block block = certified(lenient);

			lenient.commit(block);
			strict.commit(block);

			assertThat(strict.balanceOf(account(1)), equalTo(500L));
			assertThat(strict.stateRoot(), equalTo(lenient.stateRoot()));

			final Ledger restarted = open(fileSystem, "b", blacklisting(Set.of(HELD.address())));

			assertThat(restarted.balanceOf(account(1)), equalTo(500L));
		}
	}

	@Example
	public void shouldTakeTheClaimOnceTheAddressIsOffTheList() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger held = open(fileSystem, "a", blacklisting(Set.of(HELD.address())));
			final Ledger lifted = open(fileSystem, "b", blacklisting(Set.of()));

			assertThat(held.submit(HELD.claim(account(1), held.chainId())).isPresent(), is(true));
			assertThat(lifted.submit(HELD.claim(account(1), lifted.chainId())), equalTo(Optional.empty()));
		}
	}

	@Example
	public void shouldLeaveABlacklistedAddressAbleToReceiveButNeverToClaim() throws IOException {
		try (FileSystem fileSystem = Jimfs.newFileSystem(Configuration.unix())) {
			final Ledger ledger = open(fileSystem, "a", blacklisting(Set.of(HELD.address())));

			assertThat(ledger.submit(new Mint(HELD.address(), 40, reference(1))), equalTo(Optional.empty()));
			ledger.commit(certified(ledger));

			assertThat(ledger.balanceOf(HELD.address()), equalTo(40L));
			assertThat(ledger.submit(HELD.claim(account(1), ledger.chainId())).isPresent(), is(true));
			assertThat(ledger.balanceOf(HELD.address()), equalTo(40L));
		}
	}

	/* The earlier chain blacklisted 67 addresses: all of them are held back and an address next to them is not */
	@Example
	public void shouldHoldBackAListAsLongAsTheEarlierOne() {
		final Set<LegacyAddress> listed = new HashSet<>();
		final Map<LegacyAddress, Long> balances = new HashMap<>();
		final List<LegacyWallet> wallets = new ArrayList<>();

		for (int i = 0; i < 68; i++) {
			final LegacyWallet wallet = new LegacyWallet(100 + i, i % 2 == 0);

			wallets.add(wallet);
			balances.put(wallet.address(), 10L);

			if (i < 67) {
				listed.add(wallet.address());
			}
		}

		final LedgerState state = stateWithSnapshot(blacklisting(listed), FOUNDATION, 680, balances);

		for (int i = 0; i < 67; i++) {
			assertThat("address " + i, reasonOf(state.rejectionOf(wallets.get(i).claim(account(1), state.chainId()))),
				containsString("not authorized"));
		}

		assertThat(state.rejectionOf(wallets.get(67).claim(account(1), state.chainId())).isPresent(), is(false));
	}
}
