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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* The release builds in keys 0 and 1. The first change adds key 2 and the second switches key 0 off, so that keys 1
   and 2 are active after both. */
public class BoardKeysSigningTest {
	private static final byte[] ROOT = rootOf(keys(0, 1));
	private static final KeyChange ADDING = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 1, 2));
	private static final KeyChange SWITCHING_OFF = change(ROOT, List.of(ADDING), keys(1, 2), keys(1, 2));

	private static int lookups;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	private static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.archive();
		spork.setTimeStamp(Instant.now().minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	private static void installBothChanges() {
		NetworkKey.install(signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2)).chainKeys().orElseThrow());
	}

	@Example
	public void shouldSignTheHeadWithTheKeysThatItsChainMakesActive() {
		final BoardKeys spork = signed(chainOf(ADDING, SWITCHING_OFF), key(1), key(2));

		assertThat(spork.isDoublySigned(), is(true));
		assertThat(spork.isTakenByTheNetwork(), is(true));
		assertThat(spork.signerOfHead(spork.getSignature()), is(Optional.of(key(1).getPublicKey())));
		assertThrows(SigningException.class, () -> signed(chainOf(ADDING, SWITCHING_OFF), key(0), key(1)));
	}

	/* A key that the chain adds signs a proposal of it before the node goes by the chain, and is named as its signer */
	@Example
	public void shouldNameTheKeyThatAChangeAddsAsTheSignerOfItsProposal() {
		final BoardKeys proposal = signed(chainOf(ADDING, SWITCHING_OFF), key(2), key(1));

		proposal.setCosignature(null);
		assertThat(PendingSporkInfo.of(proposal).signer(), is(key(2).getPublicKey()));
	}

	@Example
	public void shouldTakeNoHeadOverAChainThatDoesNotHold() {
		final KeyChange unapproved = change(ROOT, List.of(), keys(0, 1, 2), keys(0, 2));
		final BoardKeys spork = new BoardKeys();

		spork.setData(chainOf(unapproved));
		assertThat(spork.chainKeys().isPresent(), is(false));
		assertThrows(SigningException.class, () -> signed(chainOf(unapproved), key(0), key(1)));
	}

	@Example
	public void shouldJudgeEverySporkByTheKeysThatAreInstalled() {
		final MintSupply byTheNewKey = supply(key(1), key(2));

		assertThat(byTheNewKey.isTakenByTheNetwork(), is(false));
		installBothChanges();
		assertThat(byTheNewKey.isTakenByTheNetwork(), is(true));
		assertThat(supply(key(0), key(1)).isDoublySigned(), is(false));
	}

	/* A key that was switched off authorizes nothing, but the history it signed stays readable, and a version it signed
	   is logged when the spork is signed again */
	@Example
	@SneakyThrows
	public void shouldLogAndReadAVersionThatASwitchedOffKeySigned() {
		final MintSupply earlier = supply(key(0), key(1));

		installBothChanges();
		earlier.renew();
		earlier.sign(key(1).getPrivateKey());
		earlier.cosign(key(2).getPrivateKey());

		assertThat(earlier.getSignatureLog().last().getSigners(), is(List.of(key(0).getPublicKey(),
			key(1).getPublicKey())));
		assertThat(earlier.isTakenByTheNetwork(), is(true));
	}

	/* The log of a spork names who signed each version among every key that was ever active, and says which of them
	   the board switched off */
	@Example
	@SneakyThrows
	public void shouldNameInTheLogTheKeysThatTheBoardSwitchedOffAndSaySo() {
		final MintSupply earlier = supply(key(0), key(1));

		installBothChanges();

		final SignatureLogInfo stored = SignatureLogInfo.of(earlier);

		assertThat(stored.head().signer(), is(key(0).getPublicKey()));
		assertThat(stored.head().cosigner(), is(key(1).getPublicKey()));
		assertThat(stored.switchedOff(), is(Set.of(key(0).getPublicKey())));

		earlier.renew();
		earlier.sign(key(1).getPrivateKey());
		earlier.cosign(key(2).getPrivateKey());

		final SignatureLogInfo renewed = SignatureLogInfo.of(earlier);

		assertThat(renewed.entries().get(0).signer(), is(key(0).getPublicKey()));
		assertThat(renewed.head().signer(), is(key(1).getPublicKey()));
		assertThat(renewed.switchedOff(), is(Set.of(key(0).getPublicKey())));
	}

	@Example
	public void shouldReadTheKeysOnceForBothSignatures() {
		final MintSupply spork = supply(key(0), key(1));

		new MockUp<NetworkKey>() {
			@Mock public String[] getPublicKeys() {
				lookups++;
				return new String[] { key(0).getPublicKey(), key(1).getPublicKey() };
			}
		};

		lookups = 0;
		assertThat(spork.isDoublySigned(), is(true));
		assertThat(lookups, is(1));
	}
}
