package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.DefaultSocks5InitialResponse;
import io.netty.handler.codec.socksx.v5.Socks5AuthMethod;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequest;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;


public class Socks5InitialRequestHandler extends AbstractInboundHandler<Socks5InitialRequest>{
	public static final AttributeKey<Boolean> IS_AUTHENTICATED_KEY = AttributeKey.valueOf("IS_AUTHENTICATED");
	
	
	public Socks5InitialRequestHandler() {
		//Needs to have optional or we get error in the test
		super( Socks5InitialRequest.class);
			//Optional.of(IS_AUTHENTICATED_KEY), Socks5InitialRequest.class);
			//Optional.empty(), Socks5InitialRequest.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Socks5InitialRequest req) throws Exception {
		System.out.println("Is it called twice?");

		System.out.println("typedChannelRead in Socks5InitialRequestHandler");
//				System.out.println("is it autenticated before? " +  ctx.channel().attr(IS_AUTHENTICATED_KEY).get());
//				ctx.channel().attr(IS_AUTHENTICATED_KEY).set(true);
//				System.out.println("is it autenticated now? " +  ctx.channel().attr(IS_AUTHENTICATED_KEY).get());
		//ctx.pipeline().addFirst(new Socks5PasswordAuthRequestDecoder());
		ctx.writeAndFlush(new DefaultSocks5InitialResponse(Socks5AuthMethod.PASSWORD));
//		ReferenceCountUtil.release(req);
//		ctx.fireChannelRead(req);
		
	}
}
