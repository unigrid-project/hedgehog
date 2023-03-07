/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

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

import java.util.Objects;
import java.util.function.Supplier;
import lombok.SneakyThrows;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.domains.Domain;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import org.unigrid.hedgehog.jqwik.NotNull;
import org.unigrid.hedgehog.jqwik.SuiteDomain;
import static org.unigrid.hedgehog.model.spork.BaseSporkDatabaseTest.set;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo.Overview;

public class SporkDatabaseInfoTest extends BaseSporkDatabaseTest {
	@Example
	public void shouldUseDefaultsOnEmptyDatabase() {
		final SporkDatabaseInfo info = new SporkDatabaseInfo();

		assertThat(info.getMintStorageEntries().getLastChanged(), equalTo(SporkDatabaseInfo.LASTCHANGED_NEVER));
		assertThat(info.getMintSupply().getLastChanged(), equalTo(SporkDatabaseInfo.LASTCHANGED_NEVER));
		assertThat(info.getVestingStoragEntries().getLastChanged(), equalTo(SporkDatabaseInfo.LASTCHANGED_NEVER));
	}

	private <T> void assertOverview(Overview overview, GridSpork gridSpork, Supplier<T> amountSupplier) {
		if (Objects.nonNull(gridSpork)) {
			assertThat(overview.getLastChanged(), equalTo(gridSpork.getTimeStamp().toString()));
			assertThat(overview.getAmount(), equalTo(amountSupplier.get()));
		}
	}

	@SneakyThrows
	@Property(tries = 200)
	@Domain(SuiteDomain.class)
	public void shouldSetOverviewOnPopulatedDatabase(@ForAll("provideGridSpork") @NotNull GridSpork gridSpork) {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		set(sporkDatabase, gridSpork);

		final SporkDatabaseInfo info = new SporkDatabaseInfo(sporkDatabase);

		assertOverview(info.getMintStorageEntries(), sporkDatabase.getMintStorage(), () -> {
			return ((MintStorage.SporkData) sporkDatabase.getMintStorage().getData()).getMints().size();
		});

		assertOverview(info.getMintSupply(), sporkDatabase.getMintSupply(), () -> {
			return ((MintSupply.SporkData) sporkDatabase.getMintSupply().getData()).getMaxSupply();
		});

		assertOverview(info.getVestingStoragEntries(), sporkDatabase.getVestingStorage(), () -> {
			return ((VestingStorage.SporkData) sporkDatabase.getVestingStorage().getData())
				.getVestingAddresses().size();
		});
	}
}
