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
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import org.unigrid.hedgehog.model.crypto.ActiveKeys;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;

/* The release builds in keys 0 and 1, and the board switches them off one after the other */
public class SporkCountingTest {
	private SporkDatabase database;

	@BeforeTry
	public void before() {
		buildIn(keys(0, 1));
		database = SporkDatabase.builder().build();
	}

	@AfterTry
	public void after() {
		NetworkKey.reset();
	}

	/* Signed by the two keys whether or not they are active, as anyone who holds them can */
	@SneakyThrows
	static MintSupply supply(Signature signer, Signature cosigner) {
		final MintSupply spork = new MintSupply();

		spork.archive();
		spork.setTimeStamp(Instant.now().minusSeconds(60));
		spork.<MintSupply.SporkData>getData().setMaxSupply(BigDecimal.TEN);
		spork.sign(signer.getPrivateKey());
		spork.setCosignature(cosigner.sign(spork.getSignable()));
		return spork;
	}

	private static void activate(int... keys) {
		final ActiveKeys installed = NetworkKey.getActiveKeys();

		NetworkKey.install(installed.after(List.of(publicKeysOf(keys(keys)))));
	}

	@Example
	public void shouldCountASporkThatTwoActiveKeysSigned() {
		final MintSupply spork = supply(key(0), key(1));

		database.set(spork);
		assertThat(spork.isCounting(), is(true));
		assertThat(database.counting(GridSpork.Type.MINT_SUPPLY), sameInstance(spork));
		assertThat(database.anchorOf(GridSpork.Type.MINT_SUPPLY), sameInstance(spork));
		assertThat(database.counting(GridSpork.Type.LEDGER_FEE), nullValue());
	}

	/* It is still the spork that the network took, and the version over which the next one is made */
	@Example
	public void shouldStopCountingASporkOnceTheBoardSwitchesOneOfItsSignersOff() {
		final MintSupply spork = supply(key(0), key(1));

		database.set(spork);
		activate(1, 2);

		assertThat(spork.isCounting(), is(false));
		assertThat(database.counting(GridSpork.Type.MINT_SUPPLY), nullValue());
		assertThat(database.anchorOf(GridSpork.Type.MINT_SUPPLY), sameInstance(spork));
		assertThat(spork.isTakenByTheNetwork(), is(false));
		assertThat(spork.wasTakenByTheNetwork(), is(true));
	}

	@Example
	public void shouldLetASporkAnchorNothingOnceBothOfItsSignersAreSwitchedOff() {
		final MintSupply spork = supply(key(0), key(1));

		database.set(spork);
		activate(1, 2);
		activate(2, 3);

		assertThat(database.anchorOf(GridSpork.Type.MINT_SUPPLY), nullValue());
		assertThat(database.get(GridSpork.Type.MINT_SUPPLY), sameInstance(spork));
		assertThat(spork.wasTakenByTheNetwork(), is(true));
		assertThat(supply(key(4), key(5)).wasTakenByTheNetwork(), is(false));
	}

	@Example
	public void shouldCountASporkAgainOnceActiveKeysSignedIt() {
		activate(1, 2);
		database.set(supply(key(1), key(2)));

		assertThat(database.counting(GridSpork.Type.MINT_SUPPLY).isCounting(), is(true));
	}

	@Example
	public void shouldCountNoSporkThatCannotBeEncoded() {
		final GridSpork unknown = supply(key(0), key(1));

		unknown.setType((short) 0);
		assertThat(unknown.isCounting(), is(false));
		assertThat(unknown.isAnchoring(), is(false));
	}
}
