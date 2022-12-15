package org.unigrid.hedgehog.server.socks;

import io.netty.handler.codec.socksx.v5.AbstractSocks5Message;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialResponse;

public class DefaultSocks5AuthMethodResponse extends AbstractSocks5Message implements Socks5InitialResponse {

    private final Socks5AuthMethod authMethod;

    public DefaultSocks5AuthMethodResponse(Socks5AuthMethod authMethod) {
        if (authMethod == null) {
            throw new NullPointerException("authMethod");
        }
        this.authMethod = authMethod;
    }

//    @Override
//    protected void encode(ChannelHandlerContext ctx, SocksMessage msg, ByteBuf out) throws Exception {
//
//    }
//
//    @Override
//    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
//
//    }

    @Override
    public Socks5AuthMethod authMethod() {
        return authMethod;
    }
}
