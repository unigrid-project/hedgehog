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
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import org.unigrid.hedgehog.model.collection.OptionalMap;
import org.unigrid.hedgehog.model.spork.GridSpork;
import org.unigrid.hedgehog.model.network.packet.Packet;
import org.unigrid.hedgehog.model.network.codec.api.ChunkEncoder;
import org.unigrid.hedgehog.model.network.chunk.ChunkScanner;
import org.unigrid.hedgehog.model.network.chunk.ChunkType;
import org.unigrid.hedgehog.model.network.chunk.ChunkGroup;

/**
 * GridSpork-encoder med reflection, kompatibel med generiska Chunk-systemet.
 */
public abstract class AbstractGridSporkEncoder<T extends Packet> extends AbstractMessageToByteEncoder<T> {

    private final OptionalMap<GridSpork.Type, ChunkEncoder<?>> encoders;

    @SuppressWarnings("unchecked")
    protected AbstractGridSporkEncoder() {

        encoders = (OptionalMap<GridSpork.Type, ChunkEncoder<?>>)
                   (OptionalMap<?, ?>) ChunkScanner.scan(ChunkType.ENCODER, ChunkGroup.GRIDSPORK);
    }

    public void encodeGridSpork(ChannelHandlerContext ctx, GridSpork spork, ByteBuf out) throws Exception {

        GridSpork.Type type = (GridSpork.Type) getPrivateObject(spork, "type");
        final Optional<ChunkEncoder<?>> ce = encoders.getOptional(type);

        if (!ce.isPresent()) {
            System.err.println("Unable to handle spork encoder for type " + type);
            return;
        }

        ByteBuf data = Unpooled.buffer();

        try {

            // --- write header via reflection ---
            short typeValue = (short) getPrivateObject(type, "value");
            data.writeShort(typeValue);

            data.writeShort(getPrivateShort(spork, "flags"));
            data.writeZero(4);

            data.writeLong(getPrivateLong(spork, "timeStamp"));
            data.writeLong(getPrivateLong(spork, "previousTimeStamp"));

            data.writeZero(8);

            // --- encode spork data and delta ---
            invokeEncode(ce.get(), ctx, getPrivateObject(spork, "data"), data);
            invokeEncode(ce.get(), ctx, getPrivateObject(spork, "previousData"), data);

            // --- write signature ---
            byte[] signature = (byte[]) getPrivateObject(spork, "signature");
            data.writeShort(signature.length);
            data.writeBytes(signature);

            out.writeBytes(data);

        } finally {
            data.release();
        }
    }

    // =========================
    // Reflection helpers
    // =========================

    private static void invokeEncode(ChunkEncoder<?> codec, ChannelHandlerContext ctx, Object data, ByteBuf out) throws Exception {

        Method m = codec.getClass().getMethod(
                "encodeChunk",
                ChannelHandlerContext.class,
                Object.class,
                ByteBuf.class
        );

        m.invoke(codec, ctx, data, out);
    }

    private static Object getPrivateObject(Object target, String fieldName) throws Exception {

        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);

        return f.get(target);
    }

    private static short getPrivateShort(Object target, String fieldName) throws Exception {

        return (short) getPrivateObject(target, fieldName);
    }

    private static long getPrivateLong(Object target, String fieldName) throws Exception {

        return ((java.time.Instant) getPrivateObject(target, fieldName)).toEpochMilli();
    }
}