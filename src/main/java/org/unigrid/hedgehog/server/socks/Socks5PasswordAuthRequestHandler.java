package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;
import static org.unigrid.hedgehog.server.socks.Socks5CommandRequestHandler.IS_AUTHENTICATED_KEY;


public class Socks5PasswordAuthRequestHandler extends AbstractInboundHandler<Socks5PasswordAuthRequest>{

	public Socks5PasswordAuthRequestHandler() {
		super(Optional.of(IS_AUTHENTICATED_KEY), Socks5PasswordAuthRequest.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Socks5PasswordAuthRequest req) throws Exception {
		System.out.println("Password: " + req.password());
		if(req.password().equals("SHIT")){
			ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
			ctx.write(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
		}
		else{
			ctx.write(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
		}
	}
	
}
