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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class SporkDatabaseAccessTest {
	private static List<GridSpork> oneSporkOfEveryDefinedType() {
		return Arrays.stream(GridSpork.Type.values()).filter(type -> type != GridSpork.Type.UNDEFINED)
			.map(GridSpork::create).collect(Collectors.toList());
	}

	@Example
	public void returnsEverySporkUnderItsOwnType() {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		final List<GridSpork> sporks = oneSporkOfEveryDefinedType();

		sporks.forEach(sporkDatabase::set);
		sporks.forEach(spork -> assertThat(sporkDatabase.get(spork.getType()), sameInstance(spork)));
	}

	@Example
	public void returnsNothingForTypesWithoutSpork() {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();

		oneSporkOfEveryDefinedType().forEach(spork -> assertThat(sporkDatabase.get(spork.getType()), nullValue()));
	}

	@Example
	public void replacesTheSporkOfTheSameType() {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		final List<GridSpork> firsts = oneSporkOfEveryDefinedType();
		final List<GridSpork> seconds = oneSporkOfEveryDefinedType();

		firsts.forEach(sporkDatabase::set);
		seconds.forEach(sporkDatabase::set);
		seconds.forEach(spork -> assertThat(sporkDatabase.get(spork.getType()), sameInstance(spork)));
	}

	@Example
	public void refusesUndefinedSporks() {
		final SporkDatabase sporkDatabase = SporkDatabase.builder().build();
		final GridSpork undefined = new GridSpork();

		undefined.setType(GridSpork.Type.UNDEFINED);
		assertThrows(IllegalArgumentException.class, () -> sporkDatabase.get(GridSpork.Type.UNDEFINED));
		assertThrows(IllegalArgumentException.class, () -> sporkDatabase.set(undefined));
	}
}
