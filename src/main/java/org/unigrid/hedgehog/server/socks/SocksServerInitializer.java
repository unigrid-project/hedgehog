/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.socksx.SocksPortUnificationServerHandler;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class SocksServerInitializer extends ChannelInitializer<SocketChannel> {
	
	private final EventLoopGroup group;
	
	public static class PrintHandler extends ChannelInboundHandlerAdapter{

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			System.out.println("Msg: " + msg);
			ctx.fireChannelRead(msg);
		}
		
	}
	
	
	
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline().addLast(
                //new LoggingHandler(LogLevel.DEBUG),
		
		new PrintHandler(),
		
		
		
               // new SocksPortUnificationServerHandler(),
		Socks5ServerEncoder.DEFAULT,
		new Socks5InitialRequestDecoder(),
		new Socks5InitialRequestHandler(),
		new Socks5PasswordAuthRequestDecoder(),
		new Socks5PasswordAuthRequestHandler(),
		new Socks5CommandRequestDecoder(),
		new Socks5CommandRequestHandler());
    }
}
