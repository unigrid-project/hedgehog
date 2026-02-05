/*
    Unigrid Hedgehog
    Copyright © 2021-2025 Stiftelsen The Unigrid Foundation

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)

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
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.BindException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.command.option.SocksOptions;
import org.unigrid.hedgehog.model.cdi.Eager;
import org.unigrid.hedgehog.proxy.socks.SocksInitializer;
import org.unigrid.hedgehog.proxy.socks.Tunnel;
import org.unigrid.hedgehog.server.AbstractServer;

/* The local SOCKS5 proxy. It offers no authentication, so it binds to loopback unless --socks-host says
   otherwise, and it stays off while --socks-port is 0. */
@Slf4j
@Eager @ApplicationScoped
public class SocksServer extends AbstractServer {
	private static final int COMMUNICATION_THREADS = 2;
	private static final int MAX_CONNECTIONS = 512;
	private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

	@Inject
	private Tunnel tunnel;

	private NioEventLoopGroup group;
	private Channel channel;

	@PostConstruct @SneakyThrows
	void init() {
		if (SocksOptions.getPort() == SocksOptions.OFF) {
			log.atDebug().log("No SOCKS5 proxy, --socks-port is {}", SocksOptions.OFF);
		} else {
			start(resolve(SocksOptions.getHost()), SocksOptions.getPort());
		}
	}

	/* A blank name would become the loopback address, which is not what anyone who wrote it asked for */
	private static InetAddress resolve(String host) {
		try {
			if (host.isBlank()) {
				throw new UnknownHostException("The name is blank");
			}

			return InetAddress.getByName(host);
		} catch (UnknownHostException ex) {
			throw new IllegalStateException("The SOCKS5 proxy cannot use --socks-host '" + host + "'", ex);
		}
	}

	private void start(InetAddress host, int port) throws InterruptedException {
		if (!host.isLoopbackAddress()) {
			log.atWarn().log("The SOCKS5 proxy on {} asks for no credentials: anyone who reaches it can use it",
				host.getHostAddress());
		}

		group = new NioEventLoopGroup(COMMUNICATION_THREADS);

		try {
			channel = bootstrap(group).bind(host, port).sync().channel();
		} catch (Exception ex) {
			/* Its threads are not daemon threads, so a group left running would keep a daemon that failed to
			   start from exiting */
			group.shutdownGracefully();

			if (ex instanceof BindException) {
				throw new IllegalStateException("The SOCKS5 proxy cannot bind to " + host.getHostAddress()
					+ ":" + port + ", see --socks-host and --socks-port", ex);
			}

			throw ex;
		}
	}

	ServerBootstrap bootstrap(EventLoopGroup workers) {
		return new ServerBootstrap().group(workers).channel(NioServerSocketChannel.class)
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.childHandler(new ConnectionLimit(MAX_CONNECTIONS, new SocksInitializer(tunnel)));
	}

	public boolean isListening() {
		return channel != null && channel.isActive();
	}

	@Override
	public Channel getChannel() {
		return channel;
	}

	@PreDestroy
	void destroy() {
		if (channel != null) {
			try {
				channel.close().syncUninterruptibly();
			} finally {
				/* Waited for, so the port is free again once the server is destroyed: the socket of a
				   registered channel is only released when its event loop has deregistered it */
				group.shutdownGracefully(0, SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.syncUninterruptibly();
			}
		}
	}
}
