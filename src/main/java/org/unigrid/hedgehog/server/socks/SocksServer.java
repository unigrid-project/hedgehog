/*
    Unigrid Hedgehog
    Copyright © 2021-2022 The Unigrid Foundation, UGD Software AB

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */
package org.unigrid.hedgehog.server.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import lombok.Getter;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.command.option.SocksOptions;
import org.unigrid.hedgehog.server.AbstractServer;

@ApplicationScoped
public class SocksServer extends AbstractServer {
	private static final int COMMUNICATION_THREADS = 4;
	private final NioEventLoopGroup group = new NioEventLoopGroup(COMMUNICATION_THREADS);
	@Getter private Channel channel;

	@SneakyThrows
	@PostConstruct
	private void init() {
		EventLoopGroup bossGroup = new NioEventLoopGroup(COMMUNICATION_THREADS);
		EventLoopGroup workerGroup = new NioEventLoopGroup();
		
		ServerBootstrap b = new ServerBootstrap();
		b.group(group)
			.channel(NioServerSocketChannel.class)
			.handler(new LoggingHandler(LogLevel.INFO))
			.childHandler(new SocksServerInitializer()); 
		channel = b.bind(new InetSocketAddress(SocksOptions.getHost(), SocksOptions.getPort())).sync().channel();
		
	}
	
	@PreDestroy
	public void destroy() {
		channel.close();
		group.shutdownGracefully();
	}
}
