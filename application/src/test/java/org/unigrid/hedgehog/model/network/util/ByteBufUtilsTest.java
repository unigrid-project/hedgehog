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

package org.unigrid.hedgehog.model.network.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ByteBufUtilsTest {
	@Property
	public void shouldRetainIntegrityDuringPlainReadsAndWrites(@ForAll @NotBlank @AlphaChars String string) {
		final ByteBuf buffer = Unpooled.buffer();
		ByteBufUtils.writeNullTerminatedString(string, buffer);

		final String stringAfterWrite = ByteBufUtils.readNullTerminatedString(buffer);
		assertThat(stringAfterWrite, is(string));
	}

	@Provide
	public Arbitrary<String[]> alphaCharsArray() {
		return Arbitraries.strings().alpha().array(String[].class);
	}

	@Property
	public void shouldRetainIntegrityDuringArrayReadsAndWrites(@ForAll("alphaCharsArray") String[] strings) {
		final ByteBuf buffer = Unpooled.buffer();

		ByteBufUtils.writeNullTerminatedStringArray(strings, buffer, b -> {
			b.writeMedium(strings.length);
		});

		final String[] stringsAfterWrite = ByteBufUtils.readNullTerminatedStringArray(buffer, b -> {
			return b.readMedium();
		});

		assertThat(Arrays.deepEquals(stringsAfterWrite, strings), is(true));
	}
}
