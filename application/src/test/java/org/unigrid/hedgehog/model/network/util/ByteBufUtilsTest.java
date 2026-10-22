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

package org.unigrid.hedgehog.model.network.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import java.util.Arrays;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.unigrid.hedgehog.jqwik.Expect.assertThrows;

public class ByteBufUtilsTest {
	private static final int MAX_LENGTH = 16;

	@Property
	public void shouldRetainIntegrityDuringPlainReadsAndWrites(@ForAll @NotBlank @AlphaChars String string) {
		final ByteBuf buffer = Unpooled.buffer();
		ByteBufUtils.writeNullTerminatedString(string, buffer);

		final String stringAfterWrite = ByteBufUtils.readNullTerminatedString(buffer, string.length());
		assertThat(stringAfterWrite, is(string));
	}

	@Property
	public void shouldReadATextUpToItsBound(@ForAll @IntRange(max = MAX_LENGTH) int length) {
		final ByteBuf buffer = Unpooled.buffer();

		ByteBufUtils.writeNullTerminatedString("a".repeat(length), buffer);
		buffer.writeByte('b');

		assertThat(ByteBufUtils.readNullTerminatedString(buffer, MAX_LENGTH), is("a".repeat(length)));
		assertThat(buffer.readableBytes(), is(1));
	}

	@Property
	public void shouldRefuseATextLongerThanItsBound(@ForAll @IntRange(min = MAX_LENGTH + 1, max = 4096) int length) {
		final ByteBuf buffer = Unpooled.buffer();

		ByteBufUtils.writeNullTerminatedString("a".repeat(length), buffer);
		assertThrows(CorruptedFrameException.class, () -> ByteBufUtils.readNullTerminatedString(buffer, MAX_LENGTH));
	}

	@Example
	public void shouldRefuseATextThatDoesNotEnd() {
		final ByteBuf buffer = Unpooled.buffer().writeBytes("abc".getBytes());

		assertThrows(CorruptedFrameException.class, () -> ByteBufUtils.readNullTerminatedString(buffer, MAX_LENGTH));
		assertThrows(CorruptedFrameException.class, () -> ByteBufUtils.readNullTerminatedString(Unpooled.buffer(),
			MAX_LENGTH));
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
		}, Arrays.stream(strings).mapToInt(String::length).max().orElse(0));

		assertThat(Arrays.deepEquals(stringsAfterWrite, strings), is(true));
	}

	@Example
	public void shouldRefuseAnArrayWithATextLongerThanItsBound() {
		final ByteBuf buffer = Unpooled.buffer();

		ByteBufUtils.writeNullTerminatedStringArray(new String[] { "a", "a".repeat(MAX_LENGTH + 1) }, buffer,
			b -> b.writeMedium(2));

		assertThrows(CorruptedFrameException.class, () -> ByteBufUtils.readNullTerminatedStringArray(buffer,
			ByteBuf::readMedium, MAX_LENGTH));
	}
}
