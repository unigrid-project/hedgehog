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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.transport.handler.PublishSporkChannelHandler;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* Once a node took a version it drops the proposals that the version outdates, with no lock held that another
   thread waits for to store a version of its own, so the proposals are judged by what is stored by then: one over a
   version that another thread stored meanwhile is a proposal over the version stored, and stays. The release builds in
   keys 0 and 1. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class CleanupRaceTest {
	private static final String SOURCE = "198.51.100.7";

	private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
	private final PendingSporks pending = new PendingSporks();
	private final AtomicBoolean isFirst = new AtomicBoolean(true);
	private final AtomicReference<Runnable> meanwhile = new AtomicReference<>(() -> { });

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	@SneakyThrows
	private GridSpork version(GridSpork over, long maxSupply, long secondsAgo) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		spork.setTimeStamp(now.minusSeconds(secondsAgo));
		spork.sign(key(0).getPrivateKey());
		spork.cosign(key(1).getPrivateKey());
		return spork;
	}

	/* The next version over the one given, signed once */
	@SneakyThrows
	private static GridSpork proposalOver(GridSpork stored) {
		final GridSpork proposal = GridSpork.nextVersion(stored.getType(), stored, Optional.empty());

		proposal.sign(key(0).getPrivateKey());
		return proposal;
	}

	private Optional<String> digestOfTheProposalOf(Type type) {
		return pending.proposalOf(type).map(PendingSporks::digestOf);
	}

	/* Another thread stores the newer version and takes a proposal over it right after this one stored its own */
	private void meanwhile(SporkDatabase database, GridSpork newer, GridSpork proposal) {
		if (isFirst.getAndSet(false)) {
			database.set(newer);
			assertThat(pending.offer(proposal, newer), is(true));
		}
	}

	@Example
	public void shouldKeepAProposalOverAVersionThatAnotherThreadStoredMeanwhile() {
		final GridSpork stored = version(null, 10, 60);
		final GridSpork taken = version(stored, 20, 40);
		final GridSpork newer = version(taken, 30, 20);
		final GridSpork proposal = proposalOver(newer);
		final SporkDatabase database = new SporkDatabase() {
			@Override
			public synchronized boolean replace(GridSpork judged, GridSpork spork) {
				final boolean isStored = super.replace(judged, spork);

				meanwhile(this, newer, proposal);
				return isStored;
			}
		};

		database.set(stored);

		assertThat(new PublishSporkChannelHandler().receive(taken, database, pending, new SporkLag(), SOURCE),
			is(true));
		assertThat(digestOfTheProposalOf(Type.MINT_SUPPLY), is(Optional.of(PendingSporks.digestOf(proposal))));
	}

	/* The board keys are stored under a lock of their own, and the proposals are judged once it is let go */
	@Example
	public void shouldKeepAProposalOverBoardKeysThatAnotherThreadStoredMeanwhile() {
		final byte[] root = rootOf(keys(0, 1));
		final KeyChange adding = change(root, List.of(), keys(0, 1, 2), keys(0, 1, 2));
		final KeyChange removing = change(root, List.of(adding), keys(0, 1), keys(0, 1));
		final BoardKeys taken = signed(chainOf(adding), key(0), key(1));
		final BoardKeys newer = signed(chainOf(adding, removing), key(0), key(1));
		final GridSpork proposal = proposalOver(newer);
		final SporkDatabase database = new SporkDatabase() {
			@Override
			public void set(GridSpork spork) {
				super.set(spork);

				if (spork == taken) {
					meanwhile(this, newer, proposal);
				}
			}
		};

		assertThat(BoardKeysAuthority.commit(taken, database, pending), is(true));
		assertThat(digestOfTheProposalOf(Type.BOARD_KEYS), is(Optional.of(PendingSporks.digestOf(proposal))));
	}

	/* A first version, made now and signed once, that runs what is set in meanwhile, once, after it was judged as a
	   proposal over a spork or by its signature: the other thread that offers a proposal meanwhile */
	private MintSupply judged() {
		return new MintSupply() {
			@Override
			public boolean isProposalOver(GridSpork stored, BooleanSupplier mayVerify) {
				final boolean isOver = super.isProposalOver(stored, mayVerify);

				meanwhile.getAndSet(() -> { }).run();
				return isOver;
			}

			@Override
			public boolean isValidSignature() {
				final boolean isValid = super.isValidSignature();

				meanwhile.getAndSet(() -> { }).run();
				return isValid;
			}
		};
	}

	@SneakyThrows
	private static MintSupply firstProposal(MintSupply proposal, long maxSupply, Signature signer, long secondsAgo) {
		proposal.archive();
		proposal.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		proposal.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(secondsAgo));
		proposal.sign(signer.getPrivateKey());
		return proposal;
	}

	/* Sporks of a type are equal to each other whatever they hold, so a proposal that took the place of the one judged
	   is told from it by not being the one judged, and stays. The proposal over nothing is judged against a version
	   stored since, and a proposal over that version is taken while it is. */
	@Example
	public void shouldKeepAProposalThatTookThePlaceOfTheOneJudgedAgainstTheVersionStored() {
		final MintSupply held = firstProposal(judged(), 10, key(0), 30);
		final GridSpork stored = version(null, 20, 20);
		final GridSpork proposal = proposalOver(stored);

		assertThat(pending.offer(held, null), is(true));
		meanwhile.set(() -> assertThat(pending.offer(proposal, stored), is(true)));

		pending.retainProposalsOver(Type.MINT_SUPPLY, () -> stored);

		assertThat(digestOfTheProposalOf(Type.MINT_SUPPLY), is(Optional.of(PendingSporks.digestOf(proposal))));
	}

	/* The same when the keys change: the proposal of the key switched off is judged by its signature without the lock,
	   and a proposal of a key that is active that took its place meanwhile is held, not dropped with it */
	@Example
	public void shouldKeepAProposalThatTookThePlaceOfTheOneOfAKeySwitchedOff() {
		final MintSupply held = firstProposal(judged(), 10, key(0), 30);
		final MintSupply proposal = firstProposal(new MintSupply(), 20, key(1), 20);

		assertThat(pending.offer(held, null), is(true));
		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(1, 2)))));
		meanwhile.set(() -> assertThat(pending.offer(proposal, null), is(true)));

		pending.retainProposalsOfActiveKeys();

		assertThat(digestOfTheProposalOf(Type.MINT_SUPPLY), is(Optional.of(PendingSporks.digestOf(proposal))));
	}

	/* When nothing took its place, the proposal of a key that was switched off is dropped */
	@Example
	public void shouldDropTheProposalOfAKeySwitchedOff() {
		assertThat(pending.offer(firstProposal(judged(), 10, key(0), 30), null), is(true));
		NetworkKey.install(NetworkKey.getActiveKeys().after(List.of(publicKeysOf(keys(1, 2)))));

		pending.retainProposalsOfActiveKeys();

		assertThat(pending.proposalOf(Type.MINT_SUPPLY), is(Optional.empty()));
	}
}
