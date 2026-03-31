 package org.unigrid.hedgehog.model.network.codec.api;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.packet.Packet;

public interface PacketDecoder<T> {

    Optional<T> typedDecode(ChannelHandlerContext ctx, ByteBuf in) throws Exception;

    Packet.Type getCodecType();
}