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
import java.util.Optional;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;

public class PendingSporksTest {
	private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

	private static Signature proposer;
	private static Signature cosigner;

	private Instant now;
	private PendingSporks pendingSporks;

	@SneakyThrows
	@BeforeProperty
	public void before() {
		proposer = new Signature();
		cosigner = new Signature();
		now = NOW;
		pendingSporks = new PendingSporks(() -> now);

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };
			}

			@Mock public static String[] getRetiredPublicKeys() {
				return new String[0];
			}
		};
	}

	@SneakyThrows
	private static MintSupply proposal(Duration age, Signature signer) {
		final MintSupply spork = new MintSupply();
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.TEN);
		spork.setData(data);
		spork.setPreviousData(data.empty());
		spork.setTimeStamp(NOW.minus(age));
		spork.setPreviousTimeStamp(Instant.EPOCH);
		spork.sign(signer.getPrivateKey());
		return spork;
	}

	private static MintSupply proposal(Duration age) {
		return proposal(age, proposer);
	}

	@Example
	public void shouldHoldAFreshProposal() {
		final MintSupply spork = proposal(Duration.ofMinutes(59));

		assertThat(pendingSporks.offer(spork, null), is(true));
		assertThat(pendingSporks.list(), contains(spork));
		assertThat(pendingSporks.find(PendingSporks.digestOf(spork)), is(Optional.of(spork)));
	}

	@Example
	public void shouldRefuseAProposalOlderThanItsLifetime() {
		assertThat(pendingSporks.offer(proposal(Duration.ofMinutes(61)), null), is(false));
	}

	@Example
	public void shouldRefuseAProposalFromBeyondItsLifetimeAhead() {
		assertThat(pendingSporks.offer(proposal(Duration.ofMinutes(-61)), null), is(false));
	}

	@Example
	public void shouldDropAProposalOnceItsLifetimeEnds() {
		final MintSupply spork = proposal(Duration.ofMinutes(30));

		pendingSporks.offer(spork, null);
		now = NOW.plus(Duration.ofMinutes(31));

		assertThat(pendingSporks.list(), empty());
		assertThat(pendingSporks.find(PendingSporks.digestOf(spork)), is(Optional.empty()));
	}

	@Example
	public void shouldRefuseTheSameProposalTwice() {
		final MintSupply spork = proposal(Duration.ZERO);

		pendingSporks.offer(spork, null);
		assertThat(pendingSporks.offer(spork, null), is(false));
	}

	@Example
	public void shouldKeepOnlyTheNewestProposalOfAType() {
		final MintSupply older = proposal(Duration.ofMinutes(2));
		final MintSupply newer = proposal(Duration.ofMinutes(1));

		assertThat(pendingSporks.offer(older, null), is(true));
		assertThat(pendingSporks.offer(newer, null), is(true));
		assertThat(pendingSporks.offer(older, null), is(false));
		assertThat(pendingSporks.list(), contains(newer));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseACosignedSpork() {
		final MintSupply spork = proposal(Duration.ZERO);

		spork.cosign(cosigner.getPrivateKey());
		assertThat(pendingSporks.offer(spork, null), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldRefuseAProposalByAnUnknownKey() {
		assertThat(pendingSporks.offer(proposal(Duration.ZERO, new Signature()), null), is(false));
	}

	@SneakyThrows
	@Example
	public void shouldDropAProposalTheStoredSporkSupersedes() {
		final MintSupply stored = proposal(Duration.ZERO);

		pendingSporks.offer(proposal(Duration.ofMinutes(1)), null);
		stored.cosign(cosigner.getPrivateKey());
		pendingSporks.retainProposalsOver(stored);

		assertThat(pendingSporks.list(), empty());
	}

	@SneakyThrows
	@Example
	public void shouldKeepAProposalTheStoredSporkDoesNotSupersede() {
		final MintSupply stored = proposal(Duration.ofMinutes(1));
		final MintSupply spork = proposal(Duration.ZERO);

		pendingSporks.offer(spork, null);
		stored.cosign(cosigner.getPrivateKey());
		pendingSporks.retainProposalsOver(stored);

		assertThat(pendingSporks.list(), contains(spork));
	}

	@Example
	public void shouldNameTheProposalOfAType() {
		final MintSupply spork = proposal(Duration.ZERO);

		pendingSporks.offer(spork, null);

		assertThat(pendingSporks.proposalOf(GridSpork.Type.MINT_SUPPLY), is(Optional.of(spork)));
		assertThat(pendingSporks.proposalOf(GridSpork.Type.MINT_STORAGE), is(Optional.empty()));
	}

	@Example
	public void shouldRemoveAProposalByType() {
		pendingSporks.offer(proposal(Duration.ZERO), null);
		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);

		assertThat(pendingSporks.list(), empty());
	}
}
