package org.unigrid.hedgehog.server.socks;

import io.netty.channel.ChannelHandlerContext;

public interface ProxyFlowLog {

	public void log(ChannelHandlerContext ctx);
	
}
