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
import java.security.SecureRandom;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.CheckMeter;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.KEYS;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;

/* The signers of the next version of a spork are most likely those of the version before it and of its proposal, so
   those keys are tried first: an honest version costs a check for each of its two signatures, while one that no board
   key signed is tried against every key, once. All eight keys of the pool are built in, and the board signs with the
   two whose turn would come last. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class LikelySignersTest {
	private static final SecureRandom RANDOM = new SecureRandom();

	@BeforeTry
	public void before() {
		buildIn(KEYS);
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
		LikelySigners.forget();
	}

	private static int checksOf(BooleanSupplier work) {
		final AtomicInteger checks = new AtomicInteger();

		CheckMeter.charging(() -> checks.incrementAndGet() > 0, work::getAsBoolean);
		return checks.get();
	}

	@SneakyThrows
	private static GridSpork version(GridSpork over, long maxSupply, Signature signer, Signature cosigner) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		spork.sign(signer.getPrivateKey());
		spork.cosign(cosigner.getPrivateKey());
		return spork;
	}

	/* What the versions were made with is not known to a node that hears of them */
	private static void forgetTheSigning() {
		NetworkKey.reset();
		LikelySigners.forget();
	}

	@Example
	public void shouldCheckTheTwoSignaturesOfTheNextVersionOfTheSameSignersOnce() {
		final GridSpork stored = version(null, 10, key(6), key(7));
		final GridSpork next = version(stored, 20, key(7), key(6));

		forgetTheSigning();
		assertThat(SerializationUtils.clone(stored).isDoublySigned(), is(true));
		assertThat(checksOf(() -> SerializationUtils.clone(next).canReplace(stored)), is(2));
	}

	@Example
	public void shouldTryAHeadThatNoBoardKeySignedAgainstEachKeyOnce() {
		final GridSpork stored = version(null, 10, key(6), key(7));
		final GridSpork junk = SerializationUtils.clone(stored);
		final byte[] forged = new byte[stored.getSignature().length];

		RANDOM.nextBytes(forged);
		junk.setSignature(forged);
		junk.setCosignature(forged.clone());
		forgetTheSigning();

		assertThat(checksOf(junk::isDoublySigned), lessThanOrEqualTo(KEYS.size()));
	}
}
