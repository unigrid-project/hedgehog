/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 *
 * @author PC
 */
public class Socks5GetMessageHandler extends ChannelInboundHandlerAdapter{
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		
		System.out.println("PrintMsgHandler received message: " + msg);
		ctx.fireChannelRead(msg);
    }
	
	
	
}
