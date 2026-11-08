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

package org.unigrid.hedgehog.model.util;

import java.lang.reflect.Field;
import java.util.List;
import net.jqwik.api.Example;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

public class ReflectionTest {
	private static class Grandparent {
		private int grandparentFirst;
		private int grandparentSecond;
	}

	private static class Parent extends Grandparent {
		private int parentFirst;
		private int parentSecond;
	}

	private static class Child extends Parent {
		private int childFirst;
		private int childSecond;
	}

	private static List<String> names(Class<?> clazz) {
		return Reflection.getDeclaredFieldsWithParents(clazz).stream().map(Field::getName).toList();
	}

	/* Injection into a test follows this order and the container injected last is the one that the servers use, so
	   the fields of the test itself must come after those it inherits, whatever the names of the classes are */
	@Example
	public void shouldListTheFieldsOfTheParentsBeforeTheOwnInDeclarationOrder() {
		assertThat(names(Child.class), contains("grandparentFirst", "grandparentSecond", "parentFirst",
			"parentSecond", "childFirst", "childSecond"
		));
	}

	@Example
	public void shouldListTheFieldsOfAClassWithoutParentsInDeclarationOrder() {
		assertThat(names(Grandparent.class), contains("grandparentFirst", "grandparentSecond"));
	}
}
