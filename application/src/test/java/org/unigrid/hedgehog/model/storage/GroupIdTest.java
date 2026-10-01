/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation

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

package org.unigrid.hedgehog.model.storage;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class GroupIdTest {
	@Property
	public void roundTripsThroughHex(@ForAll @Size(32) byte[] value) {
		final GroupId id = GroupId.of(value);

		assertThat(GroupId.fromHex(id.toHex()), equalTo(id));
		assertThat(id.bytes(), equalTo(value));
	}

	@Property
	public void isImmuneToCallerMutation(@ForAll @Size(32) byte[] value) {
		final GroupId id = GroupId.of(value);
		value[0]++;

		assertThat(id.bytes(), not(equalTo(value)));
	}

	@Property
	public void rejectsAnyOtherLength(@ForAll @IntRange(min = 0, max = 128) int length) {
		if (length != GroupId.SIZE) {
			assertThrows(IllegalArgumentException.class, () -> GroupId.of(new byte[length]));
		}
	}
}
