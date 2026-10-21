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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;

public class SporkDatabaseVisibilityTest {
	private static List<Field> sporkFields() {
		return Arrays.stream(SporkDatabase.class.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers())).toList();
	}

	/* The network threads store a spork and the ledger reads it from its own thread */
	@Example
	public void shouldKeepEverySporkFieldVisibleAcrossThreads() {
		final List<String> plain = sporkFields().stream().filter(field -> !Modifier.isVolatile(field.getModifiers()))
			.map(Field::getName).toList();

		assertThat(sporkFields().size(), greaterThan(0));
		assertThat(plain, empty());
	}
}
