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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.crypto.CheckMeter;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.asReleased;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.shipping;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* The chain that this node goes by and the one that its release ships are held as they are, so a longer chain that
   begins with their very bytes is checked only for the changes it adds, however much of what was checked before is
   forgotten. A chain that decides the same in other bytes, as one with an approval dropped beyond the quorum does, is
   checked whole. The release builds in keys 0, 1 and 2, and the board adds key 3 and switches it off again. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysPinTest {
	private final byte[] root = rootOf(keys(0, 1, 2));
	private List<String> builtIn;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1, 2));
		builtIn = List.of(NetworkKey.getBuiltInKeys());
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
		BoardKeyChain.forget();
		asReleased();
	}

	private static int checksOf(BooleanSupplier work) {
		final AtomicInteger checks = new AtomicInteger();

		CheckMeter.charging(() -> checks.incrementAndGet() > 0, work::getAsBoolean);
		return checks.get();
	}

	private List<KeyChange> grown(List<KeyChange> chain, int length) {
		final List<KeyChange> grown = new ArrayList<>(chain);

		while (grown.size() < length) {
			grown.add(grown.size() % 2 == 0 ? change(root, grown, keys(0, 1, 2, 3), keys(0, 1, 2, 3))
				: change(root, grown, keys(0, 1, 2), keys(0, 1, 2)));
		}

		return grown;
	}

	private static int approvalsOf(List<KeyChange> changes) {
		return changes.stream().mapToInt(change -> change.getApprovals().size()).sum();
	}

	/* Everything this node checked is gone, as when copies of other bytes pushed it out */
	private static void forgetTheChecks() {
		NetworkKey.reset();
		BoardKeyChain.forget();
	}

	private boolean verifies(List<KeyChange> chain) {
		return BoardKeyChain.verified(root, builtIn, chain).isPresent();
	}

	@Example
	public void shouldCheckOnlyTheChangesThatAChainAddsToTheOneThisNodeGoesBy() {
		final List<KeyChange> installed = grown(List.of(), 4);
		final List<KeyChange> longer = grown(installed, 5);

		assertThat(BoardKeysAuthority.install(signed(chainOf(installed.toArray(KeyChange[]::new)), key(0), key(1))),
			is(true));
		forgetTheChecks();

		assertThat(checksOf(() -> verifies(longer)), is(approvalsOf(longer.subList(4, 5))));
		assertThat(checksOf(() -> verifies(installed)), is(0));
	}

	@Example
	public void shouldCheckWholeAChainThatDecidesTheSameAsTheOneThisNodeGoesByInOtherBytes() {
		final List<KeyChange> installed = grown(List.of(), 4);
		final KeyChange first = installed.getFirst();
		final List<KeyChange.Approval> fewer = new ArrayList<>(first.getApprovals());

		fewer.removeIf(approval -> approval.getKey().equals(key(2).getPublicKey()));

		final List<KeyChange> variant = new ArrayList<>(installed);

		variant.set(0, new KeyChange(first.getSequence(), first.getMadeAt(), first.getPrevious(),
			new ArrayList<>(first.getActive()), fewer));
		assertThat(BoardKeysAuthority.install(signed(chainOf(installed.toArray(KeyChange[]::new)), key(0), key(1))),
			is(true));
		forgetTheChecks();

		assertThat(checksOf(() -> verifies(variant)), is(approvalsOf(variant)));
	}

	@Example
	public void shouldCheckOnlyTheChangesThatAChainAddsToTheOneTheReleaseShips() {
		final List<KeyChange> shipped = grown(List.of(), 3);
		final List<KeyChange> longer = grown(shipped, 4);

		shipping(shipped, true);
		BoardKeysAuthority.installTheRelease();
		forgetTheChecks();

		assertThat(checksOf(() -> verifies(longer)), is(approvalsOf(longer.subList(3, 4))));
	}
}
