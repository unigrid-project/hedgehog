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

/* A change of the board keys waits as a proposal for its co-signature, and one stolen key must not push it out with a
   renewal of the chain as it is, or with a chain that holds less. The release builds in keys 0 and 1; the node holds
   the first change, which adds key 2, and the proposal adds the second, which switches key 0 off, or another one,
   which adds key 3. */
@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class BoardKeysProposalTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange ADDING_3 = change(ROOT, List.of(ADDING), keys(0, 1, 2, 3), keys(0, 1, 2, 3));

	private BoardKeys stored;
	private PendingSporks pending;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		stored = signed(chainOf(ADDING), key(0), key(1));
		pending = new PendingSporks();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* Over the stored spork, a millisecond after the proposal given, as later proposals are */
	@SneakyThrows
	private GridSpork proposed(Optional<GridSpork> after, Signature signer, KeyChange... changes) {
		final GridSpork proposal = GridSpork.nextVersion(Type.BOARD_KEYS, stored, Optional.empty());

		proposal.setData(chainOf(changes));
		after.ifPresent(held -> proposal.setTimeStamp(held.getTimeStamp().plusMillis(1)));
		proposal.sign(signer.getPrivateKey());
		return proposal;
	}

	@SneakyThrows
	private GridSpork renewed(GridSpork after, Signature signer) {
		final GridSpork renewal = SerializationUtils.clone(stored);

		renewal.renew();
		renewal.setTimeStamp(after.getTimeStamp().plusMillis(1));
		renewal.sign(signer.getPrivateKey());
		return renewal;
	}

	@Example
	public void shouldKeepAProposalOfALongerChainOverARenewalOrAShorterChain() {
		final GridSpork change = proposed(Optional.empty(), key(1), ADDING, SWITCHING_OFF);

		assertThat(pending.offer(change, stored), is(true));
		assertThat(pending.offer(renewed(change, key(0)), stored), is(false));
		assertThat(pending.offer(proposed(Optional.of(change), key(0), ADDING), stored), is(false));
		assertThat(pending.proposalOf(Type.BOARD_KEYS), is(Optional.of(change)));
	}

	@Example
	public void shouldTakeTheNewerOfTwoProposalsOfChainsOfOneLength() {
		final GridSpork change = proposed(Optional.empty(), key(1), ADDING, SWITCHING_OFF);
		final GridSpork other = proposed(Optional.of(change), key(1), ADDING, ADDING_3);

		assertThat(pending.offer(change, stored), is(true));
		assertThat(pending.offer(other, stored), is(true));
		assertThat(pending.proposalOf(Type.BOARD_KEYS), is(Optional.of(other)));
	}
}
