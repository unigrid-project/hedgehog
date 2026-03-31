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

 package org.unigrid.hedgehog.model.network.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import java.util.List;

public class FrameDecoder extends ByteToMessageDecoder {

    public static final short MAGIC = (short) 0xCAFE;

    public static final AttributeKey<Integer> PACKET_SIZE_KEY =
            AttributeKey.valueOf("PACKET_SIZE");

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {

        if (in.readableBytes() < 8) {
            return;
        }

        in.markReaderIndex();

        short magic = in.readShort();

        if (magic != MAGIC) {
            ctx.close();
            return;
        }

        short type = in.readShort();
        int size = in.readInt();

        if (in.readableBytes() < size) {
            in.resetReaderIndex();
            return;
        }

        ByteBuf frame = in.readRetainedSlice(size);

        ctx.channel().attr(PACKET_SIZE_KEY).set(size);

        out.add(frame);
    }
}