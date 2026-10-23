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

package org.unigrid.hedgehog.model.spork;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

/* A proposal is checked without the lock of the proposals, so whatever changes in between, the keys that the node goes
   by or the spork it stores, is something that a cleanup already judged the held proposals by, and a proposal that was
   checked against what was there before is not held after it. The release builds in keys 0 and 1, and the board
   switches key 0 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class PendingSporksRaceTest {
	private final PendingSporks pending = new PendingSporks();
	private final SporkDatabase database = SporkDatabase.builder().build();

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private static MintSupply cosigned(MintSupply spork) {
		spork.cosign(key(1).getPrivateKey());
		return spork;
	}

	/* A first version, as nothing is stored yet, signed by key 0, which runs what is given once it has been checked */
	@SneakyThrows
	private static MintSupply proposal(long maxSupply, Runnable meanwhile) {
		final MintSupply proposal = new MintSupply() {
			@Override
			public boolean isProposalOver(GridSpork stored, BooleanSupplier mayVerify) {
				final boolean isOver = super.isProposalOver(stored, mayVerify);

				meanwhile.run();
				return isOver;
			}
		};

		proposal.archive();
		proposal.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		proposal.sign(key(0).getPrivateKey());
		return proposal;
	}

	@Example
	public void shouldHoldNoProposalCheckedAgainstKeysThatChangedBeforeItWasHeld() {
		final MintSupply proposal = proposal(10, () -> {
			NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(1, 2)))));
			pending.retainProposalsOfActiveKeys();
		});

		assertThat(pending.offer(proposal, () -> database.getMintSupply(), () -> true), is(false));
		assertThat(pending.list(), empty());
	}

	@Example
	@SneakyThrows
	public void shouldHoldNoProposalOverASporkThatWasReplacedBeforeItWasHeld() {
		final MintSupply replacing = cosigned(proposal(20, () -> { }));
		final MintSupply proposal = proposal(10, () -> {
			database.set(replacing);
			pending.retainProposalsOver(replacing);
		});

		assertThat(pending.offer(proposal, () -> database.getMintSupply(), () -> true), is(false));
		assertThat(pending.list(), empty());
	}

	/* Nothing changes in between, and the proposal is held as before */
	@Example
	public void shouldHoldAProposalWhenNothingChangedMeanwhile() {
		final MintSupply proposal = proposal(10, () -> { });

		assertThat(pending.offer(proposal, () -> database.getMintSupply(), () -> true), is(true));
		assertThat(pending.list().size(), is(1));
	}
}
