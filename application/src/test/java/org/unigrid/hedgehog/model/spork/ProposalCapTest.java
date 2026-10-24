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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.IntStream;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.network.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.SporkLag.Pool;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;

/* One active key makes as many proposals as it likes, each of them genuine, and every node checks each one it is sent,
   so a proposal that is taken gives nothing back of what checking it cost, and a node takes no more than so many
   proposals of a type from one key in a minute, and sends no more on. The release builds in keys 0 and 1. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ProposalCapTest {
	private static final String SOURCE = "198.51.100.7";

	private Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
	private final PendingSporks pending = new PendingSporks(() -> now);
	private int made;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* A first version, newer than the one made before it */
	@SneakyThrows
	private GridSpork proposal(Signature signer) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(++made));
		spork.setTimeStamp(now.minusSeconds(30).plusMillis(made));
		spork.sign(signer.getPrivateKey());
		return spork;
	}

	private boolean offered(Signature signer) {
		return pending.offer(proposal(signer), null);
	}

	@Example
	public void shouldTakeSoManyProposalsOfATypeFromOneKeyInAMinuteAndNoMore() {
		IntStream.range(0, PendingSporks.MOST_PROPOSALS_A_MINUTE).forEach(n -> assertThat(offered(key(0)), is(true)));

		assertThat(offered(key(0)), is(false));
		assertThat(offered(key(1)), is(true));

		now = now.plus(Duration.ofMinutes(1));
		assertThat(offered(key(0)), is(true));
	}

	@Example
	public void shouldGiveNothingBackOfWhatCheckingAProposalThatWasTakenCost() {
		final SporkLag lag = new SporkLag(() -> now);
		final GridSpork proposal = proposal(key(0));

		assertThat(new PublishSporkChannelHandler().receive(proposal, SporkDatabase.builder().build(), pending, lag,
			SOURCE, Pool.OTHER), is(true));
		assertThat(lag.left(Type.MINT_SUPPLY, Pool.OTHER, SOURCE), lessThan((double) VerifySlots.shareOf(Pool.OTHER)));
	}
}
