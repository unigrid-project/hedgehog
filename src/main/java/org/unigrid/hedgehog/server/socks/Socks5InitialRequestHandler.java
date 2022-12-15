package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;
import static org.unigrid.hedgehog.server.socks.Socks5CommandRequestHandler.IS_AUTHENTICATED_KEY;


public class Socks5InitialRequestHandler extends AbstractInboundHandler<Socks5InitialRequest>{

	public Socks5InitialRequestHandler() {
		super(Optional.of(IS_AUTHENTICATED_KEY), Socks5InitialRequest.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Socks5InitialRequest req) throws Exception {
		for(Socks5AuthMethod res : req.authMethods()){
			if(Socks5AuthMethod.PASSWORD.equals(res)){
				ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
				ctx.write(new DefaultSocks5AuthMethodResponse(Socks5AuthMethod.PASSWORD));
				return;
			}
		}
		ctx.close();
	}
}
