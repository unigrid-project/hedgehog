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

package org.unigrid.hedgehog.command.cli;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;
import net.jqwik.api.lifecycle.PropagationMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.command.cli.SporkCommands.json;
import static org.unigrid.hedgehog.command.cli.SporkCommands.tree;
import org.unigrid.hedgehog.command.util.RestCommandFixture;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Request;
import org.unigrid.hedgehog.command.util.RestCommandFixture.Result;
import org.unigrid.hedgehog.jqwik.MockitHook;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo;
import org.unigrid.hedgehog.model.spork.SporkDatabaseInfo.Overview;

@AddLifecycleHook(value = MockitHook.class, propagateTo = PropagationMode.ALL_DESCENDANTS)
public class GridSporkListTest {
	private static <T> Arbitrary<Overview<T, String>> overviews(Arbitrary<T> amounts) {
		final Arbitrary<String> changes = Arbitraries.oneOf(Arbitraries.just(SporkDatabaseInfo.LASTCHANGED_NEVER),
			Arbitraries.longs().between(0, Instant.MAX.getEpochSecond()).map(Instant::ofEpochSecond)
				.map(Instant::toString));

		return Combinators.combine(amounts, changes).as(Overview::new);
	}

	@Provide
	public Arbitrary<SporkDatabaseInfo> databases() {
		final Arbitrary<Integer> entries = Arbitraries.integers().greaterOrEqual(0);

		return Combinators.combine(overviews(entries), overviews(Arbitraries.bigDecimals()), overviews(entries))
			.as((mintStorage, mintSupply, vestingStorage) -> {
				final SporkDatabaseInfo info = new SporkDatabaseInfo();

				info.setMintStorageEntries(mintStorage);
				info.setMintSupply(mintSupply);
				info.setVestingStoragEntries(vestingStorage);
				return info;
			});
	}

	@Property(tries = 30)
	public void getsAndPrintsTheOverviewOfTheSporkDatabase(@ForAll("databases") SporkDatabaseInfo info) {
		final Result result = RestCommandFixture.run(new GridSporkList(), Response.ok(info).build());

		assertThat(result.request(), equalTo(Optional.of(new Request(HttpMethod.GET, "/gridspork", Optional.empty(),
			Optional.empty()))));

		assertThat(tree(result.out()), equalTo(tree(json(info))));
		assertThat(result.err(), equalTo(""));
	}

	@Example
	public void saysSoWhenTheDatabaseIsEmpty() {
		final Result result = RestCommandFixture.run(new GridSporkList(), Response.noContent().build());

		assertThat(result.out().lines().toList(), equalTo(List.of("No Content")));
	}
}
