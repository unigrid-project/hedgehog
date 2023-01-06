/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.client.RestClient;
import org.unigrid.hedgehog.model.Address;
import org.unigrid.hedgehog.model.ApplicationDirectory;
import org.unigrid.hedgehog.model.spork.MintStorage;
import org.unigrid.hedgehog.model.spork.MintStorage.SporkData.Location;
import org.unigrid.hedgehog.model.spork.SporkDatabase;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;

public class GridSporkResourceTest extends BaseRestClientTest {
	@Inject
	private SporkDatabase sporkDatabase;

	@Example
	@SneakyThrows
	public void shouldBeAbleToGetGridSporkOverview() {
		final RestClient client = new RestClient(server.getRest().getHostName(), server.getRest().getPort());
		final Instant now = Instant.now();

		SporkDatabaseInfo info = client.get("/gridspork").readEntity(SporkDatabaseInfo.class);
		assertThat(info.getMintStorageEntries().getLastChanged(), equalTo(SporkDatabaseInfo.LASTCHANGED_NEVER));

		sporkDatabase.setMintStorage(new MintStorage());
		sporkDatabase.getMintStorage().setData(new MintStorage.SporkData());
		sporkDatabase.getMintStorage().setTimeStamp(now);

		final MintStorage.SporkData data = ((MintStorage.SporkData) sporkDatabase.getMintStorage().getData());
		data.setMints(new HashMap<>());
		data.getMints().put(new Location(new Address("0123456789"), 1337), BigDecimal.ONE);

		info = client.get("/gridspork").readEntity(SporkDatabaseInfo.class);
		assertThat(info.getMintStorageEntries().getLastChanged(), equalTo(now.toString()));
		assertThat(info.getMintStorageEntries().getAmount(), equalTo(1));

		client.close();
	}
}
