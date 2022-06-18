package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.incubator.codec.quic.QuicStreamChannel;

public class RegisterQuicHandler extends ChannelInitializer<QuicStreamChannel> {
	final ChannelHandler[] handlers;

 	public RegisterQuicHandler(ChannelHandler ...handlers) {
		this.handlers = handlers;
	}

	@Override
	protected void initChannel(QuicStreamChannel channel) throws Exception {
		channel.pipeline().addLast(handlers);
	}

}
