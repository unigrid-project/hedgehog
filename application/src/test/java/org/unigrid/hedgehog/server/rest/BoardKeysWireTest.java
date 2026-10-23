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

package org.unigrid.hedgehog.server.rest;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.lifecycle.AfterTry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.spork.BoardKeys;
import org.unigrid.hedgehog.model.spork.KeyChange;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.buildIn;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.chainOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.change;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.key;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.keys;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.publicKeysOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.rootOf;
import static org.unigrid.hedgehog.model.spork.BoardKeysFixture.signed;

/* The board keys as a board member hands them to the node. The release builds in keys 0 and 1; the first change adds
   key 2, and the second switches keys 0 and 1 off and adds key 3. */
public class BoardKeysWireTest extends BaseRestClientTest {
	@Inject private SporkDatabase sporkDatabase;

	@AfterTry
	public void forget() {
		NetworkKey.reset();
		sporkDatabase.setBoardKeys(null);
	}

	private static Set<String> active() {
		return NetworkKey.getActiveKeys().active();
	}

	@Example
	@SneakyThrows
	public void shouldStoreAndInstallALongerChainAndRefuseAShorterOne() {
		buildIn(keys(0, 1));

		final byte[] root = rootOf(keys(0, 1));
		final KeyChange adding = change(root, List.of(), keys(0, 1, 2), keys(0, 1, 2));
		final KeyChange switchingOff = change(root, List.of(adding), keys(2, 3), keys(0, 2, 3));
		final BoardKeys shorter = signed(chainOf(adding), key(0), key(1));

		Thread.sleep(2);
		assertThat(Status.fromStatusCode(submit(signed(chainOf(adding, switchingOff), key(2), key(3))).getStatus()),
			equalTo(Status.OK));
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(2, 3)))));

		assertThat(Status.fromStatusCode(submit(shorter).getStatus()), equalTo(Status.CONFLICT));
		assertThat(sporkDatabase.getBoardKeys().<BoardKeys.SporkData>getData().getChanges().size(), equalTo(2));
		assertThat(active(), equalTo(Set.of(publicKeysOf(keys(2, 3)))));
	}
}
