/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;

public interface ChannelListener {

	public void inActive(ChannelHandlerContext ctx);
	
	public void active(ChannelHandlerContext ctx);
}
