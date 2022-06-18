/*
    Unigrid Hedgehog 
    Copyright © 2021-2022 The Unigrid Foundation

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

public class ByteBufUtils {
	public static String readNullTerminatedString(ByteBuf src) {
		final byte[] result = new byte[src.bytesBefore((byte) 0)];

		src.readBytes(result);
		src.skipBytes(1); /* Skip the null terminator */

		return new String(result, CharsetUtil.ISO_8859_1);
	}

	public static void writeNullTerminatedString(String src, ByteBuf dest) {
		dest.writeBytes(src.getBytes(CharsetUtil.ISO_8859_1));
		dest.writeZero(1); /* Null terminate */
	}
}
