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

import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.ledger.Expect.assertThrows;

public class LedgerGenesisTest {
	private static final ValidatorSet VALIDATORS = new ValidatorSet(List.of(Ed25519.publicKey(new byte[32])));

	private static byte[] seed(int first) {
		final byte[] seed = new byte[32];

		seed[0] = (byte) first;
		return seed;
	}

	@Example
	public void shouldHaveAStableChainId() {
		assertThat(new LedgerGenesis(5, 10, VALIDATORS).chainId(), equalTo(new LedgerGenesis(5, 10, VALIDATORS).chainId()));
		assertThat(new LedgerGenesis(5, 10, VALIDATORS).chainId().length, equalTo(Digests.HASH_SIZE));
	}

	@Example
	public void shouldCommitTheChainIdToEveryField() {
		final byte[] base = new LedgerGenesis(5, 10, VALIDATORS).chainId();

		assertThat(new LedgerGenesis(6, 10, VALIDATORS).chainId(), not(equalTo(base)));
		assertThat(new LedgerGenesis(5, 11, VALIDATORS).chainId(), not(equalTo(base)));
		assertThat(new LedgerGenesis(5, 10, new ValidatorSet(List.of(Ed25519.publicKey(seed(1))))).chainId(),
			not(equalTo(base)));
		assertThat(new LedgerGenesis(5, 10, new ValidatorSet(List.of(Ed25519.publicKey(seed(1)),
			Ed25519.publicKey(seed(2))))).chainId(), not(equalTo(new LedgerGenesis(5, 10,
			new ValidatorSet(List.of(Ed25519.publicKey(seed(2)), Ed25519.publicKey(seed(1))))).chainId())));
	}

	@Example
	public void shouldRefuseARoundOfNoBlocks() {
		assertThrows(IllegalArgumentException.class, () -> new LedgerGenesis(5, 0, VALIDATORS));
		assertThrows(IllegalArgumentException.class, () -> new LedgerGenesis(5, -3, VALIDATORS));
	}

	@Example
	public void shouldKeepTheGenesisValidators() {
		final LedgerGenesis genesis = new LedgerGenesis(5, 10, VALIDATORS);

		assertThat(genesis.getValidators(), equalTo(VALIDATORS));
		assertThat(genesis.getRoundLength(), equalTo(10));
		assertThat(genesis.getTime(), equalTo(5L));
	}

	private static byte[] hashOf(int fill) {
		final byte[] hash = new byte[Digests.HASH_SIZE];

		java.util.Arrays.fill(hash, (byte) fill);
		return hash;
	}

	@Example
	public void shouldHaveNoSnapshotUnlessGivenOne() {
		final LedgerGenesis genesis = new LedgerGenesis(5, 10, VALIDATORS);

		assertThat(genesis.getSnapshotHash(), equalTo(hashOf(0)));
		assertThat(genesis.getSnapshotTotal(), equalTo(0L));
	}

	@Example
	public void shouldCommitTheChainIdToTheSnapshot() {
		final byte[] base = new LedgerGenesis(5, 10, VALIDATORS).chainId();

		assertThat(new LedgerGenesis(5, 10, VALIDATORS, hashOf(1), 0).chainId(), not(equalTo(base)));
		assertThat(new LedgerGenesis(5, 10, VALIDATORS, hashOf(0), 7).chainId(), not(equalTo(base)));
		assertThat(new LedgerGenesis(5, 10, VALIDATORS, hashOf(0), 0).chainId(), equalTo(base));
	}

	@Example
	public void shouldRefuseABadSnapshot() {
		assertThrows(IllegalArgumentException.class, () -> new LedgerGenesis(5, 10, VALIDATORS, new byte[32], 0));
		assertThrows(IllegalArgumentException.class, () -> new LedgerGenesis(5, 10, VALIDATORS, hashOf(0), -1));
	}

	@Example
	public void shouldNotLetTheSnapshotHashBeChangedFromOutside() {
		final LedgerGenesis genesis = new LedgerGenesis(5, 10, VALIDATORS, hashOf(1), 3);
		final byte[] chainId = genesis.chainId();

		genesis.getSnapshotHash()[0] ^= 1;
		assertThat(genesis.chainId(), equalTo(chainId));
	}

	@Example
	public void shouldKeepItsOwnCopyOfTheSnapshotHashItIsGiven() {
		final byte[] given = hashOf(1);
		final LedgerGenesis genesis = new LedgerGenesis(5, 10, VALIDATORS, given, 3);
		final byte[] chainId = genesis.chainId();

		given[0] ^= 1;
		assertThat(genesis.chainId(), equalTo(chainId));
		assertThat(genesis.getSnapshotHash(), equalTo(hashOf(1)));
	}
}
