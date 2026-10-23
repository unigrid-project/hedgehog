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
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* Two keys that the board switched off still sign a version over the stored spork, which counts for nothing. Renewed
   by an active key, it carries the previous data and time stamp of the stored spork, so only its log tells that it
   was made from the forged version. The release builds in keys 0 and 1; the first change adds keys 2 and 3, and the
   second switches keys 0 and 1 off. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class ForgedVersionTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2, 3), keys(0, 1, 2, 3));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(2, 3), keys(1, 2, 3));

	/* The keys that the release builds in, and the chain that the board stored, installed */
	public static BoardKeys installTheBoardKeys() {
		buildIn(keys(0, 1));

		final BoardKeys boardKeys = signed(chainOf(ADDING, SWITCHING_OFF), key(2), key(3));

		NetworkKey.install(boardKeys.chainKeys().orElseThrow());
		return boardKeys;
	}

	@BeforeTry
	public void before() {
		installTheBoardKeys();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static MintSupply.SporkData supplyOf(long maxSupply) {
		final MintSupply.SporkData data = new MintSupply.SporkData();

		data.setMaxSupply(BigDecimal.valueOf(maxSupply));
		return data;
	}

	/* What the board stored two minutes ago */
	@SneakyThrows
	public static GridSpork genuine() {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		spork.setData(supplyOf(10));
		spork.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(120));
		spork.sign(key(2).getPrivateKey());
		spork.cosign(key(3).getPrivateKey());
		return spork;
	}

	/* A version over the stored spork that the two switched-off keys signed a minute ago */
	@SneakyThrows
	public static GridSpork forgedOver(GridSpork stored) {
		final GridSpork forged = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		forged.setData(supplyOf(1_000_000));
		forged.setTimeStamp(stored.getTimeStamp().plusSeconds(60));
		forged.sign(key(0).getPrivateKey());
		forged.setCosignature(key(1).sign(forged.getSignable()));
		return forged;
	}

	/* Renewed and signed once, by one stolen active key or by a board member who renews what a node hands out */
	@SneakyThrows
	public static GridSpork renewalOf(GridSpork spork, Signature signer) {
		final GridSpork renewal = SerializationUtils.clone(spork);

		renewal.renew();
		renewal.sign(signer.getPrivateKey());
		return renewal;
	}

	@SneakyThrows
	private static GridSpork nextVersionOf(GridSpork stored, Signature signer) {
		final GridSpork next = GridSpork.nextVersion(Type.MINT_SUPPLY, stored, Optional.empty());

		next.setData(supplyOf(20));
		next.sign(signer.getPrivateKey());
		return next;
	}

	@Example
	public void shouldRefuseAProposalMadeFromAVersionThatSwitchedOffKeysSignedOverTheStoredOne() {
		final GridSpork stored = genuine();
		final GridSpork laundered = renewalOf(forgedOver(stored), key(2));

		assertThat(laundered.isProposalOver(stored), is(false));
		assertThat(new PendingSporks().offer(laundered, stored), is(false));
	}

	/* A first version that the two switched-off keys signed, as anyone who holds them can, while a node may hold none */
	@SneakyThrows
	public static GridSpork forgedFirstVersion() {
		final GridSpork forged = GridSpork.nextVersion(Type.MINT_SUPPLY, null, Optional.empty());

		forged.setData(supplyOf(1_000_000));
		forged.setTimeStamp(Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60));
		forged.sign(key(0).getPrivateKey());
		forged.setCosignature(key(1).sign(forged.getSignable()));
		return forged;
	}

	/* Its renewal carries no previous version, as a first version over nothing does, but its log holds the forged one */
	@Example
	public void shouldRefuseOverNothingAProposalMadeFromAVersionThatSwitchedOffKeysSigned() {
		final GridSpork laundered = renewalOf(forgedFirstVersion(), key(2));

		assertThat(laundered.getSignatureLog().size(), is(1));
		assertThat(laundered.isProposalOver(null), is(false));
		assertThat(new PendingSporks().offer(laundered, null), is(false));
		assertThat(nextVersionOf(null, key(2)).isProposalOver(null), is(true));
	}

	@Example
	public void shouldStillTakeTheNextVersionAndTheRenewalOfTheStoredSpork() {
		final GridSpork stored = genuine();

		for (GridSpork proposal : List.of(nextVersionOf(stored, key(2)), renewalOf(stored, key(3)))) {
			assertThat(proposal.isProposalOver(stored), is(true));
			assertThat(new PendingSporks().offer(proposal, stored), is(true));
		}
	}

	/* A node that missed versions takes the co-signed version that carries them in its log, which is no proposal */
	@Example
	@SneakyThrows
	public void shouldStillTakeACoSignedVersionWhoseLogCarriesSeveralNewerVersions() {
		final GridSpork stored = genuine();
		final GridSpork missed = nextVersionOf(stored, key(2));

		missed.cosign(key(3).getPrivateKey());

		final GridSpork latest = nextVersionOf(missed, key(3));

		latest.cosign(key(2).getPrivateKey());
		assertThat(latest.canReplace(stored), is(true));
	}
}
