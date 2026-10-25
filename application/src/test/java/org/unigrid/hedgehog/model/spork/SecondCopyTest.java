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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.PropagationMode;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.CheckMeter;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;

/* Every peer sends what the network took, and a version or a change of the board keys comes again in a copy of other
   bytes, so what verified once costs no check when it comes again, from any peer and in any copy */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class SecondCopyTest {
	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	private static int checksOf(BooleanSupplier work) {
		final AtomicInteger checks = new AtomicInteger();

		CheckMeter.charging(() -> checks.incrementAndGet() > 0, work::getAsBoolean);
		return checks.get();
	}

	@SneakyThrows
	private static GridSpork version(GridSpork over, long maxSupply) {
		final GridSpork spork = GridSpork.nextVersion(Type.MINT_SUPPLY, over, Optional.empty());

		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.valueOf(maxSupply));
		spork.sign(key(0).getPrivateKey());
		spork.cosign(key(1).getPrivateKey());
		return spork;
	}

	@Example
	public void shouldCheckNoSignatureOfAVersionAgainThatACopyOfItProved() {
		buildIn(keys(0, 1));

		final GridSpork stored = version(null, 10);
		final GridSpork next = version(stored, 20);

		/* Co-signing checks both signatures, which a node that did not make the versions has not */
		NetworkKey.reset();

		assertThat(checksOf(() -> SerializationUtils.clone(next).canReplace(stored)), greaterThan(0));
		assertThat(checksOf(() -> SerializationUtils.clone(next).canReplace(stored)), is(0));
	}

	/* Whoever holds a change can drop an approval beyond the quorum, which makes a change of other bytes that decides
	   the same: keys 0, 1 and 2 are built in, and the change that adds key 3 is approved by all four, then by three */
	@Example
	public void shouldCheckNoApprovalAgainThatAnotherCopyOfItsChangeProved() {
		buildIn(keys(0, 1, 2));

		final byte[] root = rootOf(keys(0, 1, 2));
		final KeyChange adding = change(root, List.of(), keys(0, 1, 2, 3), keys(0, 1, 2, 3));
		final List<KeyChange.Approval> fewer = new ArrayList<>(adding.getApprovals());

		fewer.removeIf(approval -> approval.getKey().equals(key(2).getPublicKey()));

		final KeyChange variant = new KeyChange(adding.getSequence(), adding.getMadeAt(), adding.getPrevious(),
			new ArrayList<>(adding.getActive()), fewer);
		final List<String> builtIn = List.of(NetworkKey.getBuiltInKeys());

		assertThat(checksOf(() -> BoardKeyChain.verified(root, builtIn, List.of(adding)).isPresent()), is(4));
		assertThat(checksOf(() -> BoardKeyChain.verified(root, builtIn, List.of(variant)).isPresent()), is(0));
	}
}
