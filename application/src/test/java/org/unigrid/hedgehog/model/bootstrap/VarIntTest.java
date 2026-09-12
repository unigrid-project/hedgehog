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

package org.unigrid.hedgehog.model.bootstrap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

public class VarIntTest {
	@Property(tries = 500)
	public void shouldRoundTripEveryWidth(@ForAll @LongRange(min = 0, max = Long.MAX_VALUE) long value) {
		assertThat(readBack(value), equalTo(value));
	}

	@Example
	public void shouldRoundTripEveryWidthBoundary() {
		for (final long value : new long[] { 0, 0xfc, 0xfd, 0xffff, 0x10000, 0xffffffffL, 0x100000000L }) {
			assertThat(readBack(value), equalTo(value));
		}
	}

	private static long readBack(long value) {
		final ByteBuffer buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);

		VarInt.write(buffer, value);
		return VarInt.read(buffer.flip());
	}
}
