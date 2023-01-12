/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.unigrid.hedgehog.server.socks;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5PasswordAuthRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.logging.Level;
import lombok.Getter;

@ApplicationScoped
public class SocksServer {

	private EventLoopGroup bossGroup=new NioEventLoopGroup();

	public EventLoopGroup getBossGroup() {
		return bossGroup;
	}
	
	@Getter private Channel channel;

	private static final Logger logger = LoggerFactory.getLogger(SocksServer.class);
	
	private boolean auth;
	
	private boolean logging;
	
	private ProxyFlowLog proxyFlowLog;
	
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
	
	public SocksServer proxyFlowLog(ProxyFlowLog proxyFlowLog) {
		this.proxyFlowLog = proxyFlowLog;
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
	
	public ProxyFlowLog getProxyFlowLog() {
		return proxyFlowLog;
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
		if(proxyFlowLog == null) {
			proxyFlowLog = new ProxyFlowLog4j();
		}
		if(passwordAuth == null) {
			passwordAuth = new PropertiesPasswordAuth();
		}
		EventLoopGroup boss = new NioEventLoopGroup(2 );
		EventLoopGroup worker = new NioEventLoopGroup();
		try {
			
			ServerBootstrap bootstrap = new ServerBootstrap();
			bootstrap.group(boss, worker)
			.channel(NioServerSocketChannel.class)
			.childHandler(new ChannelInitializer<SocketChannel>() {
				@Override
				protected void initChannel(SocketChannel ch){
					ch.pipeline().addLast(new IdleStateHandler(3, 30, 0),
					new ProxyIdleHandler(),
					new LoggingHandler(),
					Socks5ServerEncoder.DEFAULT,
					new Socks5InitialRequestDecoder(),
					new Socks5InitialRequestHandler(SocksServer.this));
					if(isAuth()) {
						//socks auth
						ch.pipeline().addLast(new Socks5PasswordAuthRequestDecoder(),
						new Socks5PasswordAuthRequestHandler(getPasswordAuth()));
					}
					ch.pipeline().addLast(new Socks5CommandRequestDecoder(),
					new Socks5CommandRequestHandler(SocksServer.this.getBossGroup()));
				}
			});
			
			channel = bootstrap.bind(1081).sync().channel();
		} catch (InterruptedException ex) {
			System.out.println("Got exception");
			java.util.logging.Logger.getLogger(SocksServer.class.getName()).log(Level.SEVERE, null, ex);
		} finally {
			System.out.println("Does it go here? Finally");
		}
	}
	
	@PreDestroy
	public void destroy() {
		channel.close();
		bossGroup.shutdownGracefully();
	}
}
