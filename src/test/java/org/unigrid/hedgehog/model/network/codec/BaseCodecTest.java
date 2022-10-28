/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import org.unigrid.hedgehog.jqwik.*;
import mockit.Mocked;
import org.unigrid.hedgehog.model.network.codec.api.PacketDecoder;
import org.unigrid.hedgehog.model.network.codec.api.PacketEncoder;

public class BaseCodecTest<T> extends BaseMockedWeldTest {
	protected T encodeDecode(T entity, PacketEncoder<T> encoder, PacketDecoder<T> decoder,
		@Mocked ChannelHandlerContext context) throws Exception {

		final List<Object> out = new ArrayList<>();
		final ByteBuf encodedData = Unpooled.buffer();

		encoder.encode(context, entity, encodedData);
		decoder.decode(context, encodedData, out);
		return (T) out.get(0);
	}
}
