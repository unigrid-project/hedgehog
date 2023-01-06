package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequest;
import io.netty.handler.codec.socksx.v5.Socks5CommandType;
import io.netty.util.AttributeKey;
import java.util.Optional;
import org.unigrid.hedgehog.model.network.handler.AbstractInboundHandler;

public class Socks5CommandRequestHandler extends AbstractInboundHandler<Socks5CommandRequest>{
	public static final AttributeKey<Boolean> IS_AUTHENTICATED_KEY = AttributeKey.valueOf("IS_AUTHENTICATED");
	
	public Socks5CommandRequestHandler() {
		super(Optional.empty(), Socks5CommandRequest.class);
			//of(IS_AUTHENTICATED_KEY), Socks5CommandRequest.class);
	}

	@Override
	public void typedChannelRead(ChannelHandlerContext ctx, Socks5CommandRequest req) throws Exception {
		if (req.type() == Socks5CommandType.CONNECT){
			
			System.out.println("whatever");
			ctx.pipeline().addLast(new SocksServerConnectHandler());
			//ctx.channel().attr(IS_AUTHENTICATED_KEY).set(true);
			//System.out.println("attr should be true now: " + ctx.channel().attr(IS_AUTHENTICATED_KEY).get());
                        //ctx.pipeline().remove(this);
                        ctx.fireChannelRead(req);
		}
		else{
			System.out.println("It`s closing now!");
			ctx.close();
		}
		
	}
	
	
}
