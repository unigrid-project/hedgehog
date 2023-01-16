package org.unigrid.hedgehog.server.socks;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LoggingHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.unigrid.hedgehog.model.network.handler.GenericChannelInitializer;

@Slf4j
@ApplicationScoped
public class SocksServer {

	private EventLoopGroup bossGroup = new NioEventLoopGroup();

	public EventLoopGroup getBossGroup() {
		return bossGroup;
	}

	@Getter
	private Channel channel;

	private boolean auth;

	private boolean logging;

	private ChannelListener channelListener;

	private PasswordAuth passwordAuth;

	public SocksServer auth(boolean auth) {
		this.auth = auth;
		return this;
	}

	public SocksServer logging(boolean logging) {
		this.logging = logging;
		return this;
	}

	public SocksServer channelListener(ChannelListener channelListener) {
		this.channelListener = channelListener;
		return this;
	}

	public SocksServer passwordAuth(PasswordAuth passwordAuth) {
		this.passwordAuth = passwordAuth;
		return this;
	}

	public ChannelListener getChannelListener() {
		return channelListener;
	}

	public PasswordAuth getPasswordAuth() {
		return passwordAuth;
	}

	public boolean isAuth() {
		return auth;
	}

	public boolean isLogging() {
		return logging;
	}

	@PostConstruct
	public void start() {
		if (passwordAuth == null) {
			passwordAuth = new PropertiesPasswordAuth();
		}
		EventLoopGroup boss = new NioEventLoopGroup(2);
		EventLoopGroup worker = new NioEventLoopGroup();
		try {

			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(boss, worker)
				.channel(NioServerSocketChannel.class)
				.childHandler(new GenericChannelInitializer<SocketChannel>(() -> {
					return Arrays.asList(
						new LoggingHandler(),
						Socks5ServerEncoder.DEFAULT,
						new Socks5InitialRequestDecoder(),
						new Socks5InitialRequestHandler(SocksServer.this),
						new Socks5PasswordAuthRequestDecoder(),
						new Socks5PasswordAuthRequestHandler(getPasswordAuth()),
						new Socks5CommandRequestDecoder(),
						new Socks5CommandRequestHandler(SocksServer.this.getBossGroup())
					);
				}, GenericChannelInitializer.Type.SERVER));
			channel = bootstrap.bind(1081).sync().channel();
		} catch (InterruptedException ex) {
			log.error("Exception: " + ex);
		} finally {
		}
	}

	@PreDestroy
	public void destroy() {
		channel.close();
		bossGroup.shutdownGracefully();
	}
}
