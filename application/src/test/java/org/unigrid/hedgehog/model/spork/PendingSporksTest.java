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
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.commons.lang3.SerializationUtils;
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
		};
	}

	private static MintSupply proposal(Duration age, Signature signer) {
		return proposal(new MintSupply(), age, signer);
	}

	@SneakyThrows
	private static MintSupply proposal(MintSupply spork, Duration age, Signature signer) {
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
	public void shouldHoldAProposalOfAClockThatIsALittleAhead() {
		assertThat(pendingSporks.offer(proposal(PendingSporks.LEEWAY.negated()), null), is(true));
	}

	/* A key that stamped its proposals far ahead would have them win over every honest one until their time came */
	@Example
	public void shouldRefuseAProposalStampedFurtherAheadThanClocksDrift() {
		final MintSupply ahead = proposal(PendingSporks.LEEWAY.plusSeconds(1).negated());

		assertThat(pendingSporks.offer(ahead, null), is(false));
		assertThat(pendingSporks.offer(proposal(Duration.ZERO, cosigner), null), is(true));
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

	/* One over nothing, held before a first version was stored, is no proposal over that version, and nothing would
	   co-sign it over it */
	@SneakyThrows
	@Example
	public void shouldKeepAProposalMadeFromTheStoredSporkAndNoOther() {
		final MintSupply stored = proposal(Duration.ofMinutes(2));

		stored.cosign(cosigner.getPrivateKey());

		final GridSpork over = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.empty());

		over.setTimeStamp(NOW);
		over.sign(proposer.getPrivateKey());
		assertThat(pendingSporks.offer(over, stored), is(true));
		pendingSporks.retainProposalsOver(stored);
		assertThat(pendingSporks.list(), contains(over));

		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);
		pendingSporks.offer(proposal(Duration.ofMinutes(1)), null);
		pendingSporks.retainProposalsOver(stored);
		assertThat(pendingSporks.list(), empty());
	}

	/* What a node stores, made half an hour ago: signed by both keys */
	@SneakyThrows
	private static MintSupply stored() {
		final MintSupply stored = proposal(Duration.ofMinutes(30));

		stored.cosign(cosigner.getPrivateKey());
		return stored;
	}

	/* The next version of the stored spork, as a board member proposes a change of it */
	@SneakyThrows
	private static GridSpork changeOf(GridSpork stored, Duration age) {
		final GridSpork change = GridSpork.nextVersion(GridSpork.Type.MINT_SUPPLY, stored, Optional.empty());

		change.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.ONE);
		change.setTimeStamp(NOW.minus(age));
		change.sign(proposer.getPrivateKey());
		return change;
	}

	/* The stored spork moved to a new time, as a board member proposes it again with its data unchanged */
	@SneakyThrows
	private static GridSpork renewalOf(GridSpork stored, Duration age) {
		final GridSpork renewal = SerializationUtils.clone(stored);

		renewal.renew();
		renewal.setTimeStamp(NOW.minus(age));
		renewal.sign(proposer.getPrivateKey());
		return renewal;
	}

	/* A renewal has the data of the stored spork. One that pushed out a change that waits for its co-signature would
	   have the board co-sign the stored data again in its place, so the change stays, whichever of them is newer. */
	@Example
	public void shouldKeepAChangeThatWaitsOverANewerRenewal() {
		final MintSupply stored = stored();
		final GridSpork change = changeOf(stored, Duration.ofMinutes(2));

		assertThat(pendingSporks.offer(change, stored), is(true));
		assertThat(pendingSporks.offer(renewalOf(stored, Duration.ofMinutes(1)), stored), is(false));
		assertThat(pendingSporks.list(), contains(change));
	}

	@Example
	public void shouldHoldAChangeInThePlaceOfANewerRenewalThatWaits() {
		final MintSupply stored = stored();
		final GridSpork renewal = renewalOf(stored, Duration.ofMinutes(1));
		final GridSpork change = changeOf(stored, Duration.ofMinutes(2));

		assertThat(pendingSporks.offer(renewal, stored), is(true));
		assertThat(pendingSporks.offer(change, stored), is(true));
		assertThat(pendingSporks.offer(renewal, stored), is(false));
		assertThat(pendingSporks.list(), contains(change));
	}

	/* Between two changes, and between two renewals, the newer one is held, as before */
	@Example
	public void shouldKeepTheNewerOfTwoChangesAndOfTwoRenewals() {
		final MintSupply stored = stored();
		final GridSpork change = changeOf(stored, Duration.ofMinutes(1));
		final GridSpork renewal = renewalOf(stored, Duration.ZERO);

		assertThat(pendingSporks.offer(change, stored), is(true));
		assertThat(pendingSporks.offer(changeOf(stored, Duration.ofMinutes(3)), stored), is(false));
		assertThat(pendingSporks.list(), contains(change));

		pendingSporks.remove(GridSpork.Type.MINT_SUPPLY);
		assertThat(pendingSporks.offer(renewal, stored), is(true));
		assertThat(pendingSporks.offer(renewalOf(stored, Duration.ofMinutes(3)), stored), is(false));
		assertThat(pendingSporks.list(), contains(renewal));
	}

	/* A change that no one co-signed within its lifetime waits for nothing, and holds no place against the renewal
	   that a board member proposes then, though the two were made within an hour of each other */
	@Example
	public void shouldHoldARenewalOnceTheChangeThatWaitedIsOutOfItsLifetime() {
		final MintSupply stored = stored();
		final GridSpork renewal = renewalOf(stored, Duration.ofMinutes(-30));

		assertThat(pendingSporks.offer(changeOf(stored, Duration.ofMinutes(20)), stored), is(true));
		now = NOW.plus(Duration.ofMinutes(41));

		assertThat(pendingSporks.offer(renewal, stored), is(true));
		assertThat(pendingSporks.list(), contains(renewal));
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

	/* The digest of a proposal is worked out over all of its content, encoded and hashed, and the intake of a proposal
	   from a peer waits for the lock of the proposals on an event loop, so the digests are not worked out under it */
	@Example
	public void shouldFindAProposalByItsDigestWithoutHoldingTheLockOfTheProposals() {
		final AtomicBoolean isFinding = new AtomicBoolean();
		final AtomicBoolean isLocked = new AtomicBoolean();
		final MintSupply spork = proposal(new MintSupply() {
			@Override
			public byte[] getSignable() {
				if (isFinding.get() && Thread.holdsLock(pendingSporks)) {
					isLocked.set(true);
				}

				return super.getSignable();
			}
		}, Duration.ZERO, proposer);

		assertThat(pendingSporks.offer(spork, null), is(true));

		isFinding.set(true);
		assertThat(pendingSporks.find(PendingSporks.digestOf(spork)), is(Optional.of(spork)));
		assertThat(pendingSporks.find("no digest of a proposal held"), is(Optional.empty()));
		assertThat(isLocked.get(), is(false));
	}
}
