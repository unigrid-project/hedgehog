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
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class ByteBufUtils {
	public static String readNullTerminatedString(ByteBuf src) {
		final byte[] result = new byte[src.bytesBefore((byte) 0)];

		src.readBytes(result);
		src.skipBytes(1); /* Skip the null terminator */

		return new String(result, CharsetUtil.UTF_8);
	}

	public static void writeNullTerminatedString(String src, ByteBuf dest) {
		dest.writeBytes(src.getBytes(CharsetUtil.UTF_8));
		dest.writeZero(1); /* Null terminate */
	}

	public static <T> String[] readNullTerminatedStringArray(ByteBuf src, Function<ByteBuf, T> reader) {
		final T length = reader.apply(src);
		final List<String> strings = new ArrayList<>();

		for (int i = 0; i < (int) length; i++) {
			strings.add(readNullTerminatedString(src));
		}

		return strings.toArray(new String[0]);
	}

	public static void writeNullTerminatedStringArray(String[] src, ByteBuf dest, Consumer<ByteBuf> writer) {
		writer.accept(dest);

		for (String s : src) {
			writeNullTerminatedString(s, dest);
		}
	}
}
