package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.DefaultSocks5PasswordAuthResponse;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequest;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthStatus;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;


public class Socks5PasswordAuthRequestHandler extends AbstractInboundHandler<Socks5PasswordAuthRequest>{

	public Socks5PasswordAuthRequestHandler() {
		super(Socks5PasswordAuthRequest.class);
			//Optional.of(IS_AUTHENTICATED_KEY), Socks5PasswordAuthRequest.class);
			//Optional.empty(), Socks5PasswordAuthRequest.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Socks5PasswordAuthRequest req) throws Exception {
		System.out.println("Password: " + req.password());
		if(req.password().equals("test")){
			System.out.println("typedChannelRead in Socks5PasswordAuthRequestHandler");
			//ctx.pipeline().addFirst(new Socks5CommandRequestDecoder());
//			System.out.println("is it autenticated before? " +  ctx.channel().attr(IS_AUTHENTICATED_KEY).get());
//			ctx.channel().attr(IS_AUTHENTICATED_KEY).set(true);
//			System.out.println("is it autenticated now? " +  ctx.channel().attr(IS_AUTHENTICATED_KEY).get());
			ctx.writeAndFlush(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.SUCCESS));
			System.out.println("Password status SUCCESS");
		}
		else{
			System.out.println("not a pickle");
			ctx.write(new DefaultSocks5PasswordAuthResponse(Socks5PasswordAuthStatus.FAILURE));
		}
	}
	
}
