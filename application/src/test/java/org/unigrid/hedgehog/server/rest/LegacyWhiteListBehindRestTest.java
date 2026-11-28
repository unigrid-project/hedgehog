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
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.unigrid.hedgehog.model.bootstrap.LegacyAddress;
import org.unigrid.hedgehog.model.spork.GridSpork.Type;
import org.unigrid.hedgehog.model.spork.LegacyWhiteList;
import org.unigrid.hedgehog.model.spork.LegacyWhiteListTest;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkLag;

/**
 * A node that has seen a newer whitelist two board keys signed, without storing it, approves nothing from the one it
 * holds. In a class of its own, as what the node has seen is kept for every try of a class.
 */
public class LegacyWhiteListBehindRestTest extends BaseRestClientTest {
	private static final String URL = "/gridspork/legacy-whitelist/";
	private static final String WITHDRAWN_HASH = "1".repeat(40);
	private static final String KEPT_HASH = "2".repeat(40);
	private static final String BEHIND = "This node knows of a newer legacy whitelist than the one it holds;"
		+ " ask again once it has it";

	@Inject
	private SporkDatabase sporkDatabase;

	@Inject
	private SporkLag lag;

	/** The legacy address of a hash160. */
	private static String address(String hash) {
		return LegacyAddress.encode(HexFormat.of().parseHex(hash));
	}

	/** A whitelist of the hashes, signed and co-signed, after the given one when there is one. */
	@SneakyThrows
	private static LegacyWhiteList cosigned(Set<String> hashes, LegacyWhiteList after) {
		final LegacyWhiteList whitelist = new LegacyWhiteList();

		whitelist.setData(LegacyWhiteListTest.with(hashes));
		whitelist.archive();

		if (after != null) {
			whitelist.setTimeStamp(after.getTimeStamp().plusSeconds(1));
		}

		whitelist.sign(PROPOSER.getPrivateKey());
		whitelist.cosign(COSIGNER.getPrivateKey());
		return whitelist;
	}

	/** The status and the reason the node answers an address with. */
	@SneakyThrows
	private Map<?, ?> answerFor(String hash) {
		final Response response = client.get(URL + address(hash));

		assertThat(response.getStatus(), equalTo(Status.NOT_FOUND.getStatusCode()));
		return response.readEntity(Map.class);
	}

	/** The board took an address off in a newer list; until the node stores it, it approves nothing from the old one. */
	@SneakyThrows
	@Example
	public void shouldApproveNothingWhileANewerListIsKnownButNotHeld() {
		mockNetworkKeys();

		final LegacyWhiteList held = cosigned(Set.of(WITHDRAWN_HASH, KEPT_HASH), null);
		final LegacyWhiteList newer = cosigned(Set.of(KEPT_HASH), held);

		sporkDatabase.set(held);
		lag.record(newer, sporkDatabase, SporkLag.LOCAL);

		assertThat(lag.isBehind(Type.LEGACY_WHITELIST, sporkDatabase), is(true));
		assertThat((String) answerFor(WITHDRAWN_HASH).get(RestError.FIELD), equalTo(BEHIND));

		sporkDatabase.set(newer);

		assertThat(lag.isBehind(Type.LEGACY_WHITELIST, sporkDatabase), is(false));
		assertThat((String) answerFor(WITHDRAWN_HASH).get(RestError.FIELD),
			equalTo(address(WITHDRAWN_HASH) + " is not on the legacy whitelist"));
		assertThat(client.get(URL + address(KEPT_HASH)).getStatus(), equalTo(Status.OK.getStatusCode()));
	}
}
