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
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import lombok.SneakyThrows;
import mockit.Mock;
import mockit.MockUp;
import net.jqwik.api.Example;
import org.apache.commons.lang3.SerializationUtils;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;
import org.unigrid.hedgehog.model.crypto.NetworkKey;
import org.unigrid.hedgehog.model.crypto.Signature;
import org.unigrid.hedgehog.model.crypto.SigningException;

/* The fixture was written by a build from before the classes pinned their serialVersionUID */
public class SporkDatabaseCompatibilityTest {
	private static final String FIXTURE = "/spork/legacy-spork.db";

	private static final String FIXTURE_PUBLIC_KEY = "1949fd8bdda58def6b820f8f17fb24c6d026a3022b22b0c97b10b3"
		+ "caaa7134ef0d7683474d74ed7cd24f5a82a7bfc9e320d68a0122188153be849f3f27cf8e14a591bf527e7ef4ce42febb"
		+ "7e6958f8f4a84ccab31eedf703448b724c859ad0bcfce16e915d3a20e890271a51f9e5a998b283681eaf07c154c9d67d"
		+ "bd3ea04e86d59cc1";

	private static String[] networkKeys;

	@SneakyThrows
	private SporkDatabase legacyDatabase() {
		return SporkDatabase.load(Path.of(getClass().getResource(FIXTURE).toURI()));
	}

	@Example
	public void shouldLoadSporksWrittenByEarlierBuilds() {
		final SporkDatabase database = legacyDatabase();
		final MintStorage.SporkData mints = database.getMintStorage().getData();
		final MintSupply.SporkData supply = database.getMintSupply().getData();

		assertThat(database.getMintStorage().getTimeStamp(), equalTo(Instant.parse("2024-04-30T15:08:29.272Z")));
		assertThat(mints.getMints().values(), containsInAnyOrder(new BigDecimal("220000"), new BigDecimal("14115")));
		assertThat(supply.getMaxSupply(), equalTo(new BigDecimal("1000000000")));
		assertThat(database.get(GridSpork.Type.LEDGER_BLACKLIST), nullValue());
		assertThat(database.get(GridSpork.Type.LEDGER_FEE), nullValue());
		assertThat(database.get(GridSpork.Type.BOARD_KEYS), nullValue());
	}

	@SneakyThrows
	@Example
	public void shouldKeepSignaturesOfEarlierBuildsValid() {
		final SporkDatabase database = legacyDatabase();

		assertThat(Signature.verify(database.getMintStorage(), FIXTURE_PUBLIC_KEY), is(true));
		assertThat(Signature.verify(database.getMintSupply(), FIXTURE_PUBLIC_KEY), is(true));
	}

	/* Signed once, by a key the board no longer holds */
	@SneakyThrows
	@Example
	public void shouldCountSporksOfEarlierBuildsAsUnsigned() {
		final SporkDatabase database = legacyDatabase();
		final Signature proposer = new Signature();
		final Signature cosigner = new Signature();

		networkKeys = new String[] { proposer.getPublicKey(), cosigner.getPublicKey() };

		new MockUp<NetworkKey>() {
			@Mock public static String[] getPublicKeys() {
				return networkKeys;
			}
		};

		for (GridSpork stored : List.of(database.getMintStorage(), database.getMintSupply())) {
			final GridSpork renewed = SerializationUtils.clone(stored);

			renewed.renew();
			assertThat(stored.isTakenByTheNetwork(), is(false));
			assertThrows(SigningException.class, () -> renewed.sign(proposer.getPrivateKey()));
		}
	}
}
