/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

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
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class GridSporkTypeTest {
	@Property
	public void everyTypeIsFoundByItsValue(@ForAll GridSpork.Type type) {
		assertThat(GridSpork.Type.get(type.getValue()), equalTo(type));
	}

	@Property
	public void unassignedValuesAreUndefined(@ForAll short value) {
		Assume.that(Arrays.stream(GridSpork.Type.values()).noneMatch(type -> type.getValue() == value));
		assertThat(GridSpork.Type.get(value), equalTo(GridSpork.Type.UNDEFINED));
	}

	@Property
	public void everyDefinedTypeCreatesASporkOfItsType(@ForAll GridSpork.Type type) {
		Assume.that(type != GridSpork.Type.UNDEFINED);
		assertThat(GridSpork.create(type).getType(), equalTo(type));
	}

	@Example
	public void everyTypeHasItsWireValue() {
		assertWireValue(GridSpork.Type.UNDEFINED, 0);
		assertWireValue(GridSpork.Type.MINT_STORAGE, 1000);
		assertWireValue(GridSpork.Type.MINT_SUPPLY, 1010);
		assertWireValue(GridSpork.Type.VESTING_STORAGE, 1020);
		assertWireValue(GridSpork.Type.STORAGE, 1030);
		assertWireValue(GridSpork.Type.COMMUNICATION, 1040);
		assertWireValue(GridSpork.Type.STATISTICS_PUBKEY, 2001);
	}

	@Example
	public void valuesNextToAssignedOnesAreUndefined() {
		List<Short> neighbours = List.of((short) 999, (short) 1001, (short) 1009, (short) 1011, (short) 1019,
			(short) 1021, (short) 1029, (short) 1031, (short) 1039, (short) 1041, (short) 2000, (short) 2002,
			(short) -1, Short.MIN_VALUE, Short.MAX_VALUE);

		neighbours.forEach(value -> assertThat("value " + value, GridSpork.Type.get(value),
			equalTo(GridSpork.Type.UNDEFINED)));
	}

	@Example
	public void anUndefinedTypeCreatesNothing() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> GridSpork.create(GridSpork.Type.UNDEFINED));

		assertThat(thrown.getMessage(), equalTo("Unknown spork type supplied"));
	}

	private void assertWireValue(GridSpork.Type type, int value) {
		assertThat(type.getValue(), equalTo((short) value));
		assertThat(GridSpork.Type.get((short) value), equalTo(type));
	}
}
