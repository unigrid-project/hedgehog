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

import java.util.Comparator;
import java.util.List;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* The release builds in keys 0 and 1. The first change adds key 2, the second switches key 0 off, and another first
   change adds key 3 instead of key 2. */
public class BoardKeysSuccessionTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));
	private static final KeyChange ADDING_ANOTHER = change(ROOT, List.of(), keys(0, 1, 3), keys(0, 1, 3));

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* A version made a while after the one before it */
	private static BoardKeys later(BoardKeys.SporkData chain) {
		return later(chain, 1, 2);
	}

	@SneakyThrows
	private static BoardKeys later(BoardKeys.SporkData chain, int signer, int cosigner) {
		Thread.sleep(2);
		return signed(chain, key(signer), key(cosigner));
	}

	@Example
	public void shouldTakeAVersionThatAddsToTheStoredChain() {
		final BoardKeys stored = signed(chainOf(ADDING), key(0), key(1));

		assertThat(later(chainOf(ADDING, SWITCHING_OFF)).canReplace(stored), is(true));
		assertThat(later(chainOf(ADDING, SWITCHING_OFF)).canReplace(null), is(true));
	}

	@Example
	public void shouldTakeTheSameChainOnlyWhenItIsNewer() {
		final BoardKeys earlier = signed(chainOf(ADDING), key(0), key(1));
		final BoardKeys renewed = later(chainOf(ADDING));

		assertThat(renewed.canReplace(earlier), is(true));
		assertThat(earlier.canReplace(renewed), is(false));
		assertThat(renewed.canReplace(renewed), is(false));
	}

	@Example
	public void shouldNeverTakeAShorterChainBackHoweverNew() {
		final BoardKeys stored = signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2));

		assertThat(later(chainOf(ADDING)).canReplace(stored), is(false));
		assertThat(later(chainOf(), 0, 1).canReplace(stored), is(false));
	}

	/* Keys 0 and 1 were active at the end of the shorter chain and sign it as anyone who holds them can, but the
	   stored chain switched key 0 off */
	@Example
	@SneakyThrows
	public void shouldHoldNoProposalThatTakesTheChainBackOrElsewhere() {
		final BoardKeys stored = signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2));
		final BoardKeys shorter = later(chainOf(ADDING), 0, 1);
		final BoardKeys elsewhere = later(chainOf(ADDING_ANOTHER), 0, 3);
		final BoardKeys longer = later(chainOf(ADDING, SWITCHING_OFF));

		for (BoardKeys proposal : List.of(shorter, elsewhere, longer)) {
			proposal.setCosignature(null);
		}

		assertThat(shorter.isValidSignature(), is(true));
		assertThat(shorter.canBeProposedOver(stored), is(false));
		assertThat(elsewhere.canBeProposedOver(stored), is(false));
		assertThat(longer.canBeProposedOver(signed(chainOf(ADDING), key(0), key(1))), is(true));
	}

	/* Both are first versions, which settle by their time stamps when they make other changes; a chain does not */
	@Example
	public void shouldNeverTakeAnotherChainInPlaceOfTheStoredOne() {
		final BoardKeys stored = signed(chainOf(ADDING), key(0), key(1));

		assertThat(later(chainOf(ADDING_ANOTHER), 0, 3).canReplace(stored), is(false));
	}

	/* Whoever holds a change can drop an approval beyond the quorum or sign it again, which leaves it the same change */
	@Example
	@SneakyThrows
	public void shouldTakeAVersionThatCarriesTheStoredChangesWithOtherApprovals() {
		final BoardKeys stored = signed(chainOf(ADDING), key(0), key(1));
		final KeyChange approvedAgain = SerializationUtils.clone(ADDING);

		approvedAgain.getApprovals().get(0).setSignature(key(0).sign(ADDING.getSignable()));
		approvedAgain.getApprovals().sort(Comparator.comparing(KeyChange.Approval::getKey));
		assertThat(approvedAgain.equals(ADDING), is(false));
		assertThat(later(chainOf(approvedAgain, SWITCHING_OFF)).canReplace(stored), is(true));
	}

	/* The board can sign a new version on its own machines, which is a first version with no log, as util spork-db
	   sign makes it; the whole of its log is checked then */
	@Example
	@SneakyThrows
	public void shouldTakeAFirstVersionOverAStoredVersionWithALogAndCheckItsWholeLog() {
		final BoardKeys stored = signed(chainOf(ADDING), key(0), key(1));
		final BoardKeys forged = later(chainOf(ADDING, SWITCHING_OFF));

		Thread.sleep(2);
		stored.renew();
		stored.sign(key(0).getPrivateKey());
		stored.cosign(key(2).getPrivateKey());

		final SignatureLogEntry entry = stored.getSignatureLog().last();

		forged.setSignatureLog(new SignatureLog(List.of(entry.toBuilder().signature(entry.getCosignature()).build())));
		forged.sign(key(1).getPrivateKey());
		forged.cosign(key(2).getPrivateKey());

		assertThat(stored.isTakenByTheNetwork(), is(true));
		assertThat(later(chainOf(ADDING, SWITCHING_OFF)).canReplace(stored), is(true));
		assertThat(forged.canReplace(stored), is(false));
	}
}
